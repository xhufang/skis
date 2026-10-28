# JDBC execution options and Spring exception translation

> Availability: internal `0.2.1-SNAPSHOT` development milestone; these APIs accumulate toward the
> public `0.3.0` release and are not published as a standalone `0.2.1` release.

SKIS keeps statement behavior separate from compiled SQL plans. Timeout, fetch size, maximum rows
and query tags are immutable execution values: they do not enter the AST, parameter shape or query
plan cache key.

## Configure executor defaults

Use `Skis.Builder.executionOptions` to set defaults once during application
assembly:

```java
ExecutionOptions defaults =
    ExecutionOptions.builder()
        .statementTimeout(Duration.ofSeconds(5))
        .fetchSize(256)
        .maxRows(10_000)
        .queryTag("pet-service")
        .build();

SkisExecutor executor =
    Skis.builder(dataSource)
        .dialect(PostgreSqlDialect.INSTANCE)
        .executionOptions(defaults)
        .build();
```

Every field has an explicit unset state. Unset executor fields leave the JDBC driver default
untouched; SKIS does not call a statement setter with an invented value.
Reusing one non-empty `ExecutionOptions` instance also reuses its execution-context view, avoiding
a wrapper allocation on each Fast Path call.

## Override one statement

Query objects are immutable. `withOptions` returns another query value with the same SQL structure
and plan identity:

```java
ExecutionOptions firstPage =
    ExecutionOptions.builder()
        .statementTimeout(Duration.ofSeconds(1))
        .fetchSize(64)
        .maxRows(100)
        .queryTag("pet.first-page")
        .build();

List<Pet> pets =
    executor
        .selectFrom(PetTable.petTable)
        .where(PetTable.petTable.name().eq("Mimi"))
        .withOptions(firstPage)
        .fetchList();
```

The primary-key Fast Path and single-entity mutations use overloads:

```java
executor.findById(PetTable.petTable, id, firstPage);
executor.insertInto(PetTable.petTable).value(pet).write(firstPage);
executor.updateById(PetTable.petTable, pet, firstPage);
executor.deleteById(PetTable.petTable, id, firstPage);
```

Options are resolved independently in this order:

1. per-statement value;
2. transaction Session or executor default;
3. JDBC driver default when neither level configured the field.

Zero is not the same as unset. JDBC defines zero timeout as unlimited, zero fetch size as the
driver hint default, and zero max rows as no row limit. These values can therefore override a
non-zero executor default.

`fetchOne()` must inspect a second row to uphold its non-unique-result contract. If its effective
positive max rows is 1, SKIS applies 2 internally; the operation still returns at most one value,
but a second database row remains observable as `NonUniqueResultException`.

## Transaction Session defaults

Session defaults are resolved once when the local transaction begins. Unset session fields inherit
the executor defaults, and per-statement fields can override the effective Session values:

```java
ExecutionOptions batchSession =
    ExecutionOptions.builder().fetchSize(1_000).maxRows(0).build();

executor.inTransaction(
    batchSession,
    session -> {
      List<Pet> pets = session.selectFrom(PetTable.petTable).fetchList();
      return pets.size();
    });
```

This overload is only for SKIS-owned local transactions. `SpringConnectionProvider` continues to
reject `beginTransaction`/`inTransaction`; Spring owns that boundary.

## Timeout conversion

JDBC exposes `Statement.setQueryTimeout(int)` in whole seconds. SKIS validates and converts a
`Duration` when `ExecutionOptions` is built, not on the execution hot path:

- negative durations are rejected;
- a positive fractional second rounds up, so it never accidentally becomes JDBC zero/unlimited;
- values above `Integer.MAX_VALUE` seconds are rejected;
- zero remains an explicit request for JDBC's unlimited timeout.

The statement is prepared and parameters are bound before SKIS applies timeout, fetch size and max
rows. All three are applied before execute. A setter failure is reported with phase
`statement-configuration`; statement-close and connection-release failures remain attached in
their ownership order as suppressed exceptions.

## Spring transaction timeout

`SpringConnectionProvider` also applies the remaining timeout of the current Spring transaction to
every prepared statement. This works with the normal DataSource shared by
`JdbcTransactionManager`; a `TransactionAwareDataSourceProxy` is not required for SKIS statements.

The effective timeout is the shorter positive limit:

- a per-statement SKIS timeout can shorten, but never extend, the remaining Spring transaction;
- a longer Spring transaction cannot extend a shorter SKIS timeout;
- SKIS timeout zero disables only the SKIS limit and does not disable a Spring transaction limit;
- when SKIS leaves timeout unset, the Spring transaction limit still applies.

If the Spring transaction has already expired, statement configuration fails before execute and
Spring marks its transaction resource rollback-only. The prepared statement is closed and the
transaction-bound connection remains owned by Spring.

The JDBC boundary is the default `ConnectionProvider.configureStatement(...)` method. Non-Spring
providers retain their previous no-op behavior unless they explicitly integrate another external
transaction manager.

## Query tag security

`QueryTag` accepts 1–128 ASCII characters: letters, digits, ordinary spaces and `. _ : / -`.
Leading/trailing spaces, quotes, stars, semicolons, escapes, line breaks and other characters are
rejected before JDBC work. The fixed rendered form is:

```sql
/* skis:pet.first-page */ SELECT ...
```

Tags must describe a low-cardinality operation. Do not put parameters, entity IDs, user IDs,
tenant IDs, credentials or other sensitive/high-cardinality values in a tag. SKIS never adds those
values automatically. `clearQueryTag()` explicitly suppresses an executor or Session default tag.

Failure diagnostics fingerprint the original structural SQL, excluding the tag and all parameter
values.

## Spring exception translation

`skis-spring` provides a thread-safe `SkisExceptionTranslator` implementing Spring's
`PersistenceExceptionTranslator`:

```java
@Bean
PersistenceExceptionTranslator skisExceptionTranslator() {
  return new SkisExceptionTranslator();
}
```

The JDBC boundary performs the only SQLState/vendor-code classification and records it in the SKIS
exception. The Spring adapter consumes that result and never parses a message or reclassifies:

| SKIS database category / code                                                          | Spring exception                                                  |
|----------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| duplicate key (`DB_DUPLICATE_KEY`)                                                     | `DuplicateKeyException`                                           |
| foreign key / other constraint (`DB_FOREIGN_KEY_VIOLATION`, `DB_CONSTRAINT_VIOLATION`) | `DataIntegrityViolationException`                                 |
| timeout (`DB_TIMEOUT`)                                                                 | `QueryTimeoutException`                                           |
| query canceled (`DB_QUERY_CANCELED`)                                                   | `UncategorizedDataAccessException`                                |
| lock not available (`DB_LOCK_NOT_AVAILABLE`)                                           | `CannotAcquireLockException`                                      |
| connection failure (`DB_CONNECTION_FAILURE`, `JDBC_CONNECTION_ACQUIRE_FAILED`)         | `DataAccessResourceFailureException`                              |
| deadlock / serialization failure (`DB_DEADLOCK`, `DB_SERIALIZATION_FAILURE`)           | `ConcurrencyFailureException`                                     |
| unknown (`DB_UNCATEGORIZED`)                                                           | `UncategorizedDataAccessException`                                |
| optimistic version conflict (`MUTATION_OPTIMISTIC_LOCK_CONFLICT`)                      | `OptimisticLockingFailureException`                               |
| more than one row (`QUERY_MULTIPLE_ROWS`)                                              | `IncorrectResultSizeDataAccessException` (expected 1, observed 2) |

Query cancellation maps conservatively to `UncategorizedDataAccessException`: H2 and some drivers
reuse the cancellation state when the database enforces a timeout, so the state alone cannot prove
that the user explicitly canceled the statement. Lock acquisition failure remains distinct from a
timeout and maps to Spring's retry-oriented lock exception. The multi-row mapping reports only the
observed lower bound of two rows and never fabricates a precise total.

Translation preserves the SKIS exception as the Spring exception cause and the original
`SQLException` beneath it. Messages contain the operation, execution phase, dialect, SQL
fingerprint, SQLState, vendor code and category; they do not contain SQL text, parameters or query
tags. Execution failure is primary; later resource close/release failures are added as suppressed.
Only a release failure without a previous primary failure becomes the primary exception; an existing
SKIS exception is never wrapped again.

`SkisExceptionTranslator` returns `null` for unrelated runtime exceptions and for SKIS
configuration,
query-semantic, dialect and non-optimistic mutation failures, allowing a Spring translator chain to
continue.
