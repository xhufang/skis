# Getting started with SKIS 0.2

This guide builds a minimal plain-Java application. SKIS generates its runtime metadata at compile
time, so annotation processing is a required part of the consumer build.

The dependency snippets use `0.2.0`, the current public release. Repository CI repeats this setup
against the current reactor version using a standalone project that has no SKIS parent POM and is
not part of the SKIS reactor. Internal snapshot milestone numbers are deliberately not published.

## 1. Configure Maven

Import the SKIS BOM and choose one dialect. The application must also provide its JDBC driver and
`DataSource` implementation.

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
  <skis.version>0.2.0</skis.version>
  <h2.version>2.4.240</h2.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.xhufang</groupId>
      <artifactId>skis-bom</artifactId>
      <version>${skis.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.xhufang</groupId>
    <artifactId>skis-annotations</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.xhufang</groupId>
    <artifactId>skis-runtime</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.xhufang</groupId>
    <artifactId>skis-dialect-h2</artifactId>
  </dependency>
  <dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>${h2.version}</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.14.1</version>
      <configuration>
        <release>21</release>
        <proc>full</proc>
        <annotationProcessorPaths>
          <path>
            <groupId>io.github.xhufang</groupId>
            <artifactId>skis-processor</artifactId>
            <version>${skis.version}</version>
          </path>
        </annotationProcessorPaths>
        <annotationProcessors>
          <annotationProcessor>io.skis.processor.SkisEntityProcessor</annotationProcessor>
          <annotationProcessor>io.skis.processor.SkisEntityIndexProcessor</annotationProcessor>
          <annotationProcessor>io.skis.processor.SkisProjectionProcessor</annotationProcessor>
          <annotationProcessor>io.skis.processor.SkisProjectionIndexProcessor</annotationProcessor>
        </annotationProcessors>
      </configuration>
    </plugin>
  </plugins>
</build>
```

For PostgreSQL, replace `skis-dialect-h2` with `skis-dialect-postgresql`, add the PostgreSQL JDBC
driver, and use `PostgreSqlDialect.INSTANCE` when assembling the executor.

## 2. Declare an entity

```java
package example;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;

@SkisEntity
@Table(name = "pet")
public record Pet(
    @Id long id,
    @Column(name = "pet_name", nullable = false, length = 200) String name,
    @Version Long version) {}
```

SKIS 0.2 supports one application-assigned primary-key property. A nullable Java `@Version` value
means “initialize the inserted row at version zero”; the database column itself must be non-null.

A Simple Entity can instead be a top-level public concrete Bean. Beans require a public no-argument
constructor and public getter/setter or fluent access for every persistent property; writable public
fields are also supported. Mapping annotations on a field and its getter are merged, with conflicting
`@Column` values rejected at compile time. Lombok-generated accessors and a no-argument constructor
are supported when Lombok is active on the annotation-processor path. Immutable `@Value`,
builder-only, inherited, and all-arguments-only entity shapes are intentionally deferred.

## 3. Create the schema

SKIS 0.2 does not create or migrate schemas. For H2, the matching table is:

```sql
CREATE TABLE "pet" (
  "id" BIGINT PRIMARY KEY,
  "pet_name" VARCHAR(200) NOT NULL,
  "version" BIGINT NOT NULL
);
```

## 4. Assemble the executor

The generated classes are placed in a `skis` subpackage beside the entity package.

```java
import example.skis.PetMeta;
import example.skis.PetTable;
import io.skis.dialect.h2.H2Dialect;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;

SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
PetTable pet = PetTable.PET;
```

`SkisExecutor` is thread-safe and is intended to be created once for a configured `DataSource` and
injected into application services.

## 5. Query and mutate

```java
executor.insert(PetMeta.ENTITY, new Pet(1L, "Mimi", null));

Pet stored = executor.findById(PetMeta.ENTITY, 1L).orElseThrow();
List<Pet> matches =
    executor.selectFrom(pet).where(pet.name().eq("Mimi")).fetchList();

executor.updateById(
    PetMeta.ENTITY,
    new Pet(stored.id(), "Momo", stored.version()));
executor.deleteById(PetMeta.ENTITY, stored.id());
```

When `@Version` is present, `updateById` increments the version and throws
`OptimisticLockException` if the expected version is stale.

## 6. Use a local transaction

```java
executor.inTransaction(
    session -> {
      session.insert(PetMeta.ENTITY, new Pet(2L, "Nori", null));
      session.afterCommit(() -> publishPetCreated(2L));
      return null;
    });
```

The callback commits on normal return and rolls back on an unchecked failure. `afterCommit` work is
executed only after the JDBC commit succeeds. A callback failure is reported after every registered
callback has been attempted and does not roll back the already committed database transaction.

For an explicit boundary, close the session with try-with-resources. Closing an uncompleted session
rolls it back:

```java
try (SkisSession session = executor.beginTransaction()) {
  session.insert(PetMeta.ENTITY, new Pet(3L, "Kiki", null));
  session.commit();
}
```

Commit and rollback failures may have an unknown database outcome. SKIS will not issue a second
completion operation or run `afterCommit` callbacks in that state. See the complete
[transaction-management guide](transaction-management.md) for state restoration and failure
diagnostics.

## 7. Join a Spring-managed transaction

Add `skis-spring`, construct the executor with `SpringConnectionProvider`, and let Spring own the
transaction boundary:

```java
@Bean
SkisExecutor skisExecutor(DataSource dataSource) {
  return SkisExecutorFactory.create(
      new SpringConnectionProvider(dataSource), PostgreSqlDialect.INSTANCE);
}

@Transactional
public void createPet(Pet pet) {
  skisExecutor.insert(PetMeta.ENTITY, pet);
}
```

Use `@Transactional` or `TransactionTemplate`, not `executor.inTransaction`, with this provider.
SKIS 0.2 does not include Spring Boot auto-configuration; bean and transaction-manager assembly is
explicit.

## 8. Add a generated projection

```java
@SkisProjection(entity = Pet.class)
public record PetSummary(Long id, String name) {}

PetSummary summary =
    executor
        .selectProjection(pet, PetSummary.class)
        .where(pet.id().eq(1L))
        .fetchOne()
        .orElseThrow();
```

Projection properties must match mapped entity properties by name and compatible Java type, unless
`@ProjectionProperty` explicitly selects a different property.

## 9. Diagnose annotation-processing failures

Entity and projection declaration failures use stable `SKISxxx` codes and include a short `Fix:`
hint at the relevant source element. The [annotation-processing error guide](apt-error-codes.md)
contains the cause, invalid and valid examples, and complete repair steps for every stable code.

Runtime assembly also validates every generated entity/projection index. If two dependency modules
declare the same Provider, entity Java type, or projection result type, the configuration exception
identifies both index URLs and line numbers. Do not discard one index entry while shading; rebuild
the owning modules so each generated model has one Provider.

## 10. Choose compatible JDBC column types

SKIS has built-in generated mappings for primitive and boxed scalar values, strings, exact numeric
values, `byte[]`, UUID, Java time, and legacy `java.sql` date/time values. Applications still own
DDL, including column precision and scale. See the
[PostgreSQL and H2 JDBC type mapping matrix](jdbc-type-mappings.md) for concrete column types,
nullability rules, numeric boundaries, and time-zone semantics.

Enums, LOBs, custom converters, arrays other than primitive `byte[]`, and structured JSON object
mapping are not part of 0.2. Unsupported persistent property types fail annotation processing with
`SKIS022` instead of falling back to reflection or `ResultSet.getObject` guessing.

## 11. Know the 0.2 boundary

Queries are currently limited to one table and at most one non-null equality predicate. There is no
join, association mapping, generated-key retrieval, composite ID, sorting, pagination, native SQL
entry point, schema migration, batch write, upsert, or Spring Boot auto-configuration. Use direct
JDBC alongside SKIS when an application needs SQL outside this boundary.

A complete version of this setup lives in [`skis-example-h2`](../skis-examples/skis-example-h2).
The stricter standalone consumer fixture used by CI lives under
[`skis-integration-tests/src/consumer/minimal-h2`](../skis-integration-tests/src/consumer/minimal-h2)
and verifies BOM import, annotation processing, generated indexes, runtime assembly, the H2 dialect,
and the application-supplied driver without inheriting repository build configuration.
