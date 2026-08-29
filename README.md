# SKIS ORM

> **Status: experimental 0.1 preview**

SKIS is a Java 21 JDBC micro-ORM focused on generated, reflection-free metadata and predictable
single-table operations. Version 0.1 is suitable for evaluation and small controlled services;
its API may still change before 1.0 and it is not yet a production-support release.

## Implemented scope

- compile-time entity metadata, typed table expressions, row decoders, binders, and projection indexes;
- application-assigned single-column IDs and optional optimistic locking with `@Version`;
- `findById`, single-table entity/scalar/generated-projection queries with one equality predicate;
- generated `insert`, `updateById`, and `deleteById` operations;
- local JDBC transactions and Spring transaction-bound `DataSource` connections;
- PostgreSQL and H2 dialects with a documented JDBC type-mapping contract.

## Requirements

- Java 21 or later
- Maven 3.9 or later
- a user-managed `DataSource`, JDBC driver, and database schema

## Maven setup

Import the public BOM and add the runtime, annotations, and one implemented dialect:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.xhufang</groupId>
      <artifactId>skis-bom</artifactId>
      <version>0.1.0</version>
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
    <artifactId>skis-dialect-postgresql</artifactId>
  </dependency>
</dependencies>
```

SKIS requires its annotation processor during compilation. The complete Maven compiler setup is
documented in the [getting-started guide](docs/getting-started.md).

## Minimal model

```java
@SkisEntity
@Table(name = "pet")
public record Pet(
    @Id long id,
    @Column(name = "pet_name", nullable = false) String name,
    @Version Long version) {}
```

Simple Entities may also be top-level public concrete Beans. A Bean must have a public no-argument
constructor and public getter/setter or fluent read/write access for every persistent property;
public fields are also supported. Field and getter mapping annotations are merged and conflicting
`@Column` declarations are compile-time errors. Lombok may generate the constructor/accessors when
it is enabled as an annotation processor; immutable `@Value`, builder-only, inheritance, and
all-args-only entity shapes remain outside the 0.1 contract.

Compilation generates `PetMeta`, `PetTable`, binders, decoders, and runtime index entries. The
executor then discovers them without classpath scanning:

```java
SkisExecutor executor = SkisExecutorFactory.create(dataSource, PostgreSqlDialect.INSTANCE);
executor.insert(PetMeta.ENTITY, new Pet(1L, "Mimi", null));
Pet stored = executor.findById(PetMeta.ENTITY, 1L).orElseThrow();
executor.updateById(PetMeta.ENTITY, new Pet(1L, "Momo", stored.version()));
executor.deleteById(PetMeta.ENTITY, 1L);
```

See the complete [plain Java + H2 example](skis-examples/skis-example-h2) for schema creation,
annotation processing, typed queries, mutations, and transactions.

For Spring Framework applications, add `skis-spring`, assemble the executor with
`SpringConnectionProvider`, and use Spring `@Transactional` or `TransactionTemplate` boundaries.
SKIS does not create a second transaction context. See
[transaction management](docs/transaction-management.md) for both ownership models and failure
semantics.

## Supported databases

| Database | 0.1 status |
| --- | --- |
| PostgreSQL 16 | Query and mutation integration contract |
| H2 | Development, example, and integration-test dialect |
| MySQL, MariaDB, SQL Server, Oracle, Db2, SQLite | Planned; not published in 0.1 |

JDBC drivers are deliberately supplied and versioned by the application.

## Current limitations

Version 0.1 intentionally does not provide joins, associations, generated-key retrieval, composite
IDs, sorting, pagination, streaming, native SQL entry points, schema migration, batch writes,
upsert, graph writes, caching, multitenancy, or Spring Boot auto-configuration. Equality predicates
accept one non-null value. Enum, LOB, custom converter, database array, and structured JSON object
mappings are also deferred. Applications own DDL and assign identifiers before insert.

## Documentation

- [Getting started](docs/getting-started.md)
- [Local JDBC and Spring transaction management](docs/transaction-management.md)
- [PostgreSQL and H2 JDBC type mappings](docs/jdbc-type-mappings.md)
- [Annotation-processing error guide](docs/apt-error-codes.md)
- [0.1.0 release checklist](docs/release-checklist.md)
- [0.0.5 JDBC and dialect foundation](docs/0.0.5-foundation.md)
- [0.0.6 injected executor and reflection-free read slice](docs/0.0.6-read-slice.md)
- [0.0.7 reflection-free writes and transaction semantics](docs/0.0.7-write-transaction-slice.md)
- [0.0.8 scalar and generated projection slice](docs/0.0.8-projection-slice.md)
- [0.0.9 entity-bound projection API refactor](docs/0.0.9-projection-refactor.md)

## Release history note

The Maven Central `0.0.4` publication was incomplete and contains only an empty `skis-parent` POM.
It is not an API baseline. Version `0.1.0` is the first complete public SKIS release.

## License

Licensed under the [Apache License 2.0](LICENSE).
