# JDBC execution options and Spring exception translation

> Availability: internal `0.2.1-SNAPSHOT` development milestone; these APIs accumulate toward the
> public `0.3.0` release and are not published as a standalone `0.2.1` release.

SKIS keeps statement behavior separate from compiled SQL plans. Timeout, fetch size, maximum rows
and query tags are immutable execution values: they do not enter the AST, parameter shape or query
plan cache key.

## Configure executor defaults

Use `SkisExecutorFactory.Builder.executionOptions` to set defaults once during application
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
    SkisExecutorFactory.builder()
        .dataSource(dataSource)
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
        .selectFrom(PetTable.PET)
        .where(PetTable.PET.name().eq("Mimi"))
        .withOptions(firstPage)
        .fetchList();
```

The primary-key Fast Path and single-entity mutations use overloads:

```java
executor.findById(PetMeta.ENTITY, id, firstPage);
executor.insert(PetMeta.ENTITY, pet, firstPage);
executor.updateById(PetMeta.ENTITY, pet, firstPage);
executor.deleteById(PetMeta.ENTITY, id, firstPage);
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
      List<Pet> pets = session.selectFrom(PetTable.PET).fetchList();
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
  return new SkisExceptionTranslator(PostgreSqlDialect.INSTANCE);
}
```

The selected dialect classifies SQLState/vendor codes before the adapter maps them:

| SKIS category | Spring exception |
| --- | --- |
| duplicate key | `DuplicateKeyException` |
| foreign key / other constraint | `DataIntegrityViolationException` |
| timeout | `QueryTimeoutException` |
| query canceled | `UncategorizedDataAccessException` |
| lock not available | `CannotAcquireLockException` |
| connection failure | `DataAccessResourceFailureException` |
| deadlock / serialization failure | `ConcurrencyFailureException` |
| unknown | `UncategorizedDataAccessException` |
| optimistic version conflict | `OptimisticLockingFailureException` |

Query cancellation maps conservatively to `UncategorizedDataAccessException`: H2 and some drivers
reuse the cancellation state when the database enforces a timeout, so the state alone cannot prove
that the user explicitly canceled the statement. Lock acquisition failure remains distinct from a
timeout and maps to Spring's retry-oriented lock exception.

Translation preserves the SKIS exception as the Spring exception cause and the original
`SQLException` beneath it. Messages contain the operation, execution phase, dialect, SQL
fingerprint, SQLState, vendor code and category; they do not contain SQL text, parameters or query
tags.

`SkisExceptionTranslator` returns `null` for unrelated runtime exceptions and for SKIS validation
failures without a JDBC cause, allowing a Spring translator chain to continue.
