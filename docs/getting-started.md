# Getting started with SKIS 0.1

This guide builds a minimal plain-Java application. SKIS generates its runtime metadata at compile
time, so annotation processing is a required part of the consumer build.

## 1. Configure Maven

Import the SKIS BOM and choose one dialect. The application must also provide its JDBC driver and
`DataSource` implementation.

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
  <skis.version>0.1.0</skis.version>
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

SKIS 0.1 supports one application-assigned primary-key property. A nullable Java `@Version` value
means “initialize the inserted row at version zero”; the database column itself must be non-null.

## 3. Create the schema

SKIS 0.1 does not create or migrate schemas. For H2, the matching table is:

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

## 6. Use a transaction

```java
executor.inTransaction(
    session -> {
      session.insert(PetMeta.ENTITY, new Pet(2L, "Nori", null));
      session.afterCommit(() -> publishPetCreated(2L));
      return null;
    });
```

The callback commits on normal return and rolls back on an unchecked failure. `afterCommit` work is
executed only after the JDBC commit succeeds.

## 7. Add a generated projection

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

## 8. Know the 0.1 boundary

Queries are currently limited to one table and at most one non-null equality predicate. There is no
join, association mapping, generated-key retrieval, composite ID, sorting, pagination, native SQL
entry point, schema migration, batch write, upsert, or Spring Boot auto-configuration. Use direct
JDBC alongside SKIS when an application needs SQL outside this boundary.

A complete version of this setup lives in [`skis-example-h2`](../skis-examples/skis-example-h2).
