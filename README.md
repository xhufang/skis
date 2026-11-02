# SKIS

SKIS is an independent, type-safe Java ORM built on JDBC, immutable SQL structures and
annotation-processor-generated metadata, binders and row decoders. The technical baseline is
Java 21 and Maven 3.9+.

## Version

The repository version is **0.3.0**. Final candidate validation and publication follow the
[release checklist](docs/release-checklist.md).
See [CHANGELOG](CHANGELOG.md) for the published and unreleased changes.

The examples below describe the `0.3.0` API. Before `1.0.0`, APIs and generated-code contracts may
change between versions. Use the annotation processor, runtime and dialect from the same SKIS version.

## Capabilities

| Area | Available in 0.3.0 |
| --- | --- |
| Generated models | Entity tables, metadata, binders and decoders; companion mappers for user-written records and classes |
| Queries | Typed expressions, joins, subqueries, derived sources, aggregates, GROUP BY/HAVING and offset Page/Slice/count |
| Results | Entities, scalars, `Row2`/`Row3`, generated projections and `SingleRow` with explicit SQL NULL semantics |
| Writes | INSERT, conditional UPDATE/DELETE, entity operations by ID, INSERT SELECT, typed RETURNING and entity batches |
| Keys and dialect writes | Assigned, identity, UUID v4 and Snowflake IDs; PostgreSQL ON CONFLICT and UPDATE FROM/DELETE USING; H2 MERGE |
| Execution | Bounded shared plans, JDBC transactions, PostgreSQL cursors/streams, read-only listeners and opt-in SQL/slow logging |
| Integration | Explicit JDBC dialects and connection providers, custom JDBC mappings and Spring transaction integration |

SQL structure and invocation values are separate. The default execution path uses generated binders
and row decoders, reads columns by index and reuses plans through bounded caches without retaining
invocation values or JDBC resources. Applications explicitly select the data they fetch.

## Database support

| Database | Verified baseline | Scope |
| --- | --- | --- |
| PostgreSQL | PostgreSQL 16.15 / PgJDBC 42.7.14 | Primary production dialect; materialized reads, writes and incremental cursor/stream reads |
| H2 | H2 2.4.240 | Development and tests; materialized reads and supported writes; cursor/stream terminals are rejected |

Dialect-specific writes preserve each database's SQL and concurrency semantics. Choose supported
statements using the [write support and limits](docs/mutation-returning.md).

## Query and write

Applications declare entities with `@SkisEntity`. APT generates `PetTable`, `PetMeta`, binders,
decoders and runtime models; a `Pet` entity has the table singleton `PetTable.petTable`.
Use the `0.3.0` BOM (`io.github.xhufang:skis-bom`) to align dependencies, add `skis-annotations`,
`skis-runtime` and the chosen dialect, configure `skis-processor` as an annotation processor and supply
the JDBC driver explicitly. The standalone [PostgreSQL POM](skis-integration-tests/src/consumer/minimal-postgresql/pom.xml)
and [H2 POM](skis-integration-tests/src/consumer/minimal-h2/pom.xml) provide complete configurations
without a SKIS parent POM.

With an existing `DataSource`, schema and generated model, create one thread-safe executor with an
explicit dialect:

```java
SkisExecutor executor = Skis.create(dataSource, PostgreSqlDialect.INSTANCE);
PetTable pet = PetTable.petTable;

int inserted = executor.insertInto(pet).value(new Pet(7L, "Mimi", null)).write();
Optional<Pet> byId = executor.findById(pet, 7L);
SingleRow<Pet> selected = executor.selectFrom(pet).where(pet.id().eq(7L)).fetchOne();
List<Pet> pets = executor.selectFrom(pet).orderBy(pet.id().asc()).fetchList();
```

This example uses the three-field `Pet` in the
[independent PostgreSQL consumer](skis-integration-tests/src/consumer/minimal-postgresql/src/main/java/example/Pet.java).
The [consumer projects](skis-integration-tests/src/consumer/README.md) also demonstrate projections,
extension SPIs and generated-model assembly.

Executor and Session queries execute at `fetchList()`, `fetchOne()` and other terminals. Independent
`Sql` queries build `QueryRequest<T>` values at those terminals; pass the request to `run`:

```java
SelectQuery<Pet> namedPets = Sql.selectFrom(pet).where(pet.name().eq("Mimi"));
List<Pet> matches = executor.run(namedPets.fetchList());
```

`SingleRow<R>` distinguishes no row, a row containing SQL NULL and a non-null row. `findById`
returns `Optional<E>`. Two or three selected expressions produce `Row2`/`Row3`; wider projections
use generated `*Pro` mappers for application-defined result types.

Writes execute at `write()`. Without RETURNING they report affected rows; typed RETURNING produces
`Written<R>` with `affectedRows()`, `rows()` and `one()`:

```java
var renamed = executor.update(pet)
    .set(pet.name(), "Mimi II")
    .where(pet.id().eq(7L))
    .returning(pet.id(), pet.name())
    .write();
```

Conditional UPDATE/DELETE require a WHERE clause unless `allowAllRows()` is explicit.
`update(table)` executes the SET/WHERE clauses you specify; entity `updateById` retains metadata
version rules. See [writes, batches and RETURNING](docs/mutation-returning.md).

## PostgreSQL streams

Use a transaction Session and try-with-resources. Cursors and streams must be consumed sequentially
and closed on their creating thread:

```java
try (SkisSession session = executor.beginTransaction();
    Stream<Pet> rows = session.selectFrom(pet).orderBy(pet.id().asc()).stream()) {
  rows.forEach(System.out::println);
}
```

PostgreSQL resource terminals default to a fetch size of 256 and require an active transaction,
a positive effective fetch size and extended query protocol. H2 supports materialized reads only.
See [driver conditions and resource ownership](docs/cursor-and-stream.md).

## Current limits

- Raw SQL queries/commands and typed SQL fragments are deferred to the `0.3.x` development line.
- Grouped/derived keyset pagination, CTEs, set operations, window functions and locking syntax are
  outside the current query API.
- Nonempty JDBC batch RETURNING, entity-by-ID VALUES batches and H2 MERGE/KEY entity collections
  are rejected. Use supported forms described in the [mutation guide](docs/mutation-returning.md).
- A MySQL dialect, Spring Boot auto-configuration/Starter, FetchPlan, graph writes, object caches
  and schema management remain future work.

## Documentation

| Topic | Guide |
| --- | --- |
| Runtime assembly, entities and reads | [Executor and generated models](docs/0.0.6-read-slice.md) |
| Writes, optimistic versions and transactions | [Mutation guide](docs/0.0.7-write-transaction-slice.md), [transactions](docs/transaction-management.md) |
| Primary keys | [ID strategies, generated keys and Snowflake deployment](docs/id-generation.md) |
| Staged writes and RETURNING | [Conditional writes, results and limits](docs/mutation-returning.md) |
| User records and classes | [Reusable projections](docs/0.0.8-projection-slice.md), [declared property sources](docs/0.0.9-projection-refactor.md) |
| SQL queries | [Expressions](docs/sql-expressions-and-semantic-validation.md), [joins](docs/joins.md), [subqueries](docs/subqueries.md), [pagination](docs/pagination.md) |
| Aggregates and grouped results | [Aggregate types, GROUP BY/HAVING and pagination](docs/aggregates-and-grouping.md) |
| Resource ownership and observation | [Cursors and streams](docs/cursor-and-stream.md), [execution listeners](docs/execution-listeners.md) |
| SQL logging | [Opt-in SQL/slow logging and parameter redaction](docs/sql-logging.md) |
| Extensions | [Dialect SPI and compilation](docs/dialect-compilation.md), [JDBC mappings](docs/jdbc-type-mappings.md) |
| Diagnostics | [Runtime errors](docs/runtime-error-codes.md), [APT errors](docs/apt-error-codes.md), [execution options and Spring translation](docs/execution-options-and-exception-translation.md) |

The historical numeric filenames above remain stable links. Public application and extension
contracts use API/SPI packages. `*.internal` packages and infrastructure assembly classes are
outside the application extension contract.

## Development and release

CI covers Java 21/25, generated-code and nullness checks, SQL golden tests, real PostgreSQL/H2
contracts, independent consumers, architecture, branch coverage and resource lifecycles. See
[CONTRIBUTING](CONTRIBUTING.md) for the checks and [SECURITY](SECURITY.md) for vulnerability reporting.
Final candidate evidence, the 17-GAV public component list, sources/Javadoc, signatures and SBOM
are governed by the [release checklist](docs/release-checklist.md). Tests and examples are not published.

Licensed under [Apache License 2.0](LICENSE); see [NOTICE](NOTICE).
