# Cursor and stream resource ownership

`QueryCursor<R>` and the JDK `Stream<R>` returned by `SelectQuery.stream()` provide incremental
JDBC-backed reading. They are resource owners, not pagination continuations, and they are not
thread-safe.

## Query cursor

Use a cursor with try-with-resources whenever iteration may stop early:

```java
try (QueryCursor<Pet> cursor = query.cursor()) {
  while (cursor.advance()) {
    Pet pet = cursor.current();
    // consume the current row
  }
}
```

`current()` is valid only after a successful `advance()` and before the next advance, exhaustion or
close. Calling it before positioning or after closure throws `IllegalStateException`. Normal
exhaustion closes the cursor automatically; `close()` is idempotent.

An abandoned cursor is detected by a JVM cleaner, which emits a warning containing only the
dialect and SQL fingerprint. It does not release JDBC resources from the cleaner thread because
externally managed providers may require release on the acquiring thread. Detection is a diagnostic
safety net, not a replacement for try-with-resources.

The cursor owns and closes resources in this fixed order:

1. `ResultSet`
2. `PreparedStatement`
3. connection release through `ConnectionProvider`

If reading or decoding fails, that failure remains primary and close failures are suppressed. If
closing starts without an earlier failure, the first close failure is primary and later failures
are suppressed. SQL failures identify result-set close, statement close and connection release as
separate lifecycle phases without including SQL parameter values.

## JDK stream

`stream()` returns a sequential JDK stream whose close handler owns the query cursor:

```java
try (Stream<Pet> rows = query.stream()) {
  Optional<Pet> first = rows.filter(Pet::active).findFirst();
}
```

The stream advances the same cursor lazily. Reaching normal exhaustion closes it automatically.
Its spliterator cannot split or prefetch rows. Database resource streams do not support parallel
consumption even if application code calls `parallel()`.
Short-circuit operations such as `findFirst`, `limit` or an exception from user code do not imply
full exhaustion, so the stream must remain in try-with-resources.

The thread that creates the cursor or stream owns it. Reading, decoding and closing must occur on
that thread; a cross-thread read or close is rejected before JDBC is touched and does not transfer
cleanup responsibility. Materialize and close the database resource before processing results in
parallel.

Nullable scalar queries expose `QueryCursor<@Nullable V>` and `Stream<@Nullable V>`; a row whose
selected SQL value is `NULL` is still a present cursor row.

## Execution listeners

When a listener is registered, cursor and stream operations remain open until JDBC exhaustion,
explicit close or an actual failure. Exhaustion reports `EXHAUSTED`; explicit close before exhaustion
reports `CLOSED_EARLY`. Short-circuit consumption does not report completion until the resource is
closed. Thread ownership failures and abandoned resources do not manufacture completion events.
See [read-only execution listeners](execution-listeners.md) for IDs, row counts and failure boundaries.
