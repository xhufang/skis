# Cursor and stream resource ownership

`QueryCursor<R>` and `CloseableQueryStream<R>` provide incremental JDBC-backed reading in
`0.2.3-SNAPSHOT`. They are resource owners, not pagination continuations, and they are not
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

## Closeable stream

`stream()` returns a wrapper rather than a bare `Stream<R>`:

```java
try (CloseableQueryStream<Pet> rows = query.stream()) {
  Optional<Pet> first = rows.stream().filter(Pet::active).findFirst();
}
```

The stream advances the same cursor lazily. Reaching normal exhaustion closes it automatically.
Its spliterator cannot split, so requesting a parallel stream never performs concurrent cursor
access; callers should still treat it as a sequential resource stream.
Short-circuit operations such as `findFirst`, `limit` or an exception from user code do not imply
full exhaustion, so the wrapper must remain in try-with-resources. Closing the Java stream also
closes the wrapper, but owning the wrapper explicitly makes the lifecycle unambiguous.

Nullable scalar queries expose `QueryCursor<@Nullable V>` and
`CloseableQueryStream<@Nullable V>`; a row whose selected SQL value is `NULL` is still a present
cursor row.
