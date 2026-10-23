# SKIS

SKIS is an independent, type-safe Java ORM built on JDBC, immutable SQL structures and
annotation-processor-generated metadata, binders and row decoders. The technical baseline is
Java 21 and Maven 3.9+.

## Version and availability

The latest public release is **0.2.0**. This working tree is **0.2.8-SNAPSHOT**. The engineering
foundation milestone closed on 2026-10-23; the next snapshot is a separate initialization step.
Internal `0.2.x-SNAPSHOT`
milestones accumulate toward the next public **0.3.0** release and do not create standalone
releases or tags.

The examples and topic documents in this branch describe the current snapshot. Current PostgreSQL
and H2 contracts have passed CI with their real JDBC drivers. Aggregates and GROUP BY/HAVING,
generated keys, batch writes, raw SQL and Spring Boot integration retain their later milestone
assignments. See [CHANGELOG](CHANGELOG.md) for the published and unreleased boundaries.

The reactor now declares 21 root modules after removing 23 future placeholders and the unready
Boot example. Existing production modules, the H2 example and test facilities remain. The public
release list stays at 17 GAVs; tests, examples and the frozen empty benchmark are not published.

## Query and write

Applications declare entities with `@SkisEntity`. APT generates `PetTable`, `PetMeta`, binders,
decoders and runtime models; a `Pet` entity has the table singleton `PetTable.petTable`.
Create one thread-safe executor with an explicit dialect:

```java
SkisExecutor executor = Skis.create(dataSource, PostgreSqlDialect.INSTANCE);
PetTable pet = PetTable.petTable;

executor.insert(pet, new Pet(7L, "Mimi", null));
Optional<Pet> byId = executor.findById(pet, 7L);
SingleRow<Pet> selected = executor.selectFrom(pet).where(pet.id().eq(7L)).fetchOne();
List<Pet> pets = executor.selectFrom(pet).orderBy(pet.id().asc()).fetchList();
```

This example uses the three-field `Pet` in the
[independent PostgreSQL consumer](skis-integration-tests/src/consumer/minimal-postgresql/src/main/java/example/Pet.java).
The [consumer projects](skis-integration-tests/src/consumer/README.md) show complete BOM, APT,
runtime, dialect and explicit driver dependency configuration without a SKIS parent POM.

`SelectQuery<R>` describes the selected result. `SingleRow<R>` preserves no row, a SQL NULL row
and a non-null row; `findById` retains its `Optional<E>` contract. Values are bound separately
from SQL structure. Shared plans have bounded ownership, real cache statistics and explicit clear
operations. Streams and cursors must be consumed sequentially and closed on their creating thread.

## Documentation

| Topic | Guide |
| --- | --- |
| Runtime assembly, entities and reads | [Executor and generated models](docs/0.0.6-read-slice.md) |
| Writes, optimistic versions and transactions | [Mutation guide](docs/0.0.7-write-transaction-slice.md), [transactions](docs/transaction-management.md) |
| User records and classes | [Reusable projections](docs/0.0.8-projection-slice.md), [declared property sources](docs/0.0.9-projection-refactor.md) |
| SQL queries | [Expressions](docs/sql-expressions-and-semantic-validation.md), [joins](docs/joins.md), [subqueries](docs/subqueries.md), [pagination](docs/pagination.md) |
| Resource ownership and observation | [Cursors and streams](docs/cursor-and-stream.md), [execution listeners](docs/execution-listeners.md) |
| SQL logging | [Opt-in SQL/slow logging and parameter redaction](docs/sql-logging.md) |
| Extensions | [Dialect SPI and compilation](docs/dialect-compilation.md), [JDBC mappings](docs/jdbc-type-mappings.md) |
| Diagnostics | [Runtime errors](docs/runtime-error-codes.md), [APT errors](docs/apt-error-codes.md), [execution options and Spring translation](docs/execution-options-and-exception-translation.md) |

The historical numeric filenames above remain stable links; their examples describe the current
snapshot. Public application and extension contracts use API/SPI packages. `*.internal` packages
and infrastructure assembly classes are outside the application extension contract.

## Development and release

The engineering foundation passed [CI](https://github.com/xhufang/skis/actions/runs/39957930561)
and [Compliance](https://github.com/xhufang/skis/actions/runs/39957930562) at commit `9dbb932`.
Java 21/25 each executed 76 required ordinary suites (729 tests), with separate real PostgreSQL
container/URL paths and independent consumers. See [CONTRIBUTING](CONTRIBUTING.md) for the
static-analysis, architecture, report-completeness and branch-coverage gates.

See [CONTRIBUTING](CONTRIBUTING.md), [SECURITY](SECURITY.md) and the
[release checklist](docs/release-checklist.md). Performance benchmarks remain frozen throughout
`0.2.x` development. A separate reviewed verification task must establish and execute performance
measurements after feature freeze and before public `0.3.0`; structural tests are not performance
measurements.

Licensed under [Apache License 2.0](LICENSE); see [NOTICE](NOTICE).
