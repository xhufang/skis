# Cursor and stream resource ownership

`ExecutableSelectQuery<R>.cursor()` returns `QueryCursor<R>` and `.stream()` returns a JDK
`Stream<R>`. Independent `SelectQuery<R>` terminals return requests, executed by executor/Session
`run`. Both use the same incremental JDBC reader and resource lifetime. They are not thread-safe.

The 0.2.10 cursor/stream contract passed [CI](https://github.com/xhufang/skis/actions/runs/41395524566)
at PR #101 merge commit `9a7db594`, including real PostgreSQL container/URL paths, pool return,
local/Spring transactions and independent consumers. No local build or test was run.

## Driver and transaction conditions

| Dialect / driver | cursor / stream | Conditions |
| --- | --- | --- |
| PostgreSQL / PgJDBC 42.7.14 baseline | Incremental server-portal reading | `autoCommit=false`, positive effective fetch size, a single SQL statement, extended protocol, forward-only/read-only, close at commit |
| H2 | Rejected with `SKISQ035` | Use `fetchList` or another materialized terminal |
| External dialect | Unsupported by default | Supply an immutable `CursorReadSupport` policy and independently verify the driver contract |

PostgreSQL defaults to 256 rows per fetch **only for cursor/stream**. Option precedence is per-call
override, then Session/executor defaults, then the streaming default. Explicit zero remains zero
and fails with `SKISQ036`; it is never silently replaced. Ordinary materialized queries retain
their existing JDBC defaults, including explicit zero.

PgJDBC simple protocol and wrappers that cannot expose the supported driver connection are rejected.
The PostgreSQL dialect has a provided PgJDBC dependency for direct driver-mode inspection; applications
still supply their own runtime driver. No runtime reflection or hidden validation SQL is used.
The repository parent pins the dialect's provided driver and contract-test driver to 42.7.14;
the independent consumer explicitly selects the same version. Current real-driver regression evidence
uses 42.7.14; historical evidence retains its original driver version.
The lexical single-statement check runs once when a compiled plan is assembled, never per row or
on an execution cache hit. Ambiguous backslash string syntax is conservatively rejected by resource
terminals; ordinary DSL values are bound parameters.

These conditions follow the [PgJDBC cursor contract](https://jdbc.postgresql.org/documentation/query/#getting-results-based-on-a-cursor)
and [H2 result-set behavior](https://h2database.github.io/html/advanced.html#result_sets).
SKIS does not start transactions or change an external connection's transaction state to make a
resource terminal succeed. An acquired connection is released according to its provider's ownership
rules when validation fails.

## Query cursor

Use a cursor with try-with-resources whenever iteration may stop early:

```java
try (SkisSession session = executor.beginTransaction();
    QueryCursor<Pet> cursor = session.selectFrom(PetTable.petTable).cursor()) {
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
The cleaner action holds only SQL/dialect text and a closure flag, so transaction registrations
cannot make it retain the cursor or its JDBC resources.

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
try (SkisSession session = executor.beginTransaction();
    Stream<Pet> rows = session.run(Sql.selectFrom(PetTable.petTable).stream())) {
  Optional<Pet> first = rows.findFirst();
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

## Transaction completion and pools

Local Sessions close still-open cursors before commit, rollback or closing the transaction. Early
cursor close removes its registration. Connection state is restored by the owning transaction before
provider release; cursor code itself does not change auto-commit, isolation or read-only state.
Local and Spring scopes use direct registration handles: removal takes constant time and closing
all remaining cursors takes linear time, in reverse-open order.

`SpringConnectionProvider` registers one resource scope per transaction. Its synchronization runs
before Spring's connection cleanup, supports transaction suspension/resumption and removes closed
cursors immediately. Spring retains commit/rollback ownership. A manually managed external
transaction/provider must uphold the `ConnectionProvider.registerCursor` contract or arrange
explicit close before ending its transaction or returning its connection.

For Spring `PROPAGATION_NESTED` and savepoints created through `TransactionStatus`, rollback closes
only cursors opened after that savepoint, before the JDBC rollback occurs. Earlier cursors remain
open, and releasing a savepoint keeps its cursors in the surrounding transaction. This also applies
when the first cursor is opened after the savepoint was created. Direct JDBC savepoint operations
do not emit Spring callbacks; their caller must close affected cursors explicitly.
If cursor cleanup fails during savepoint rollback, Spring can still perform the database rollback.
The scope retains the failure and reports it at transaction completion; an attempted outer commit
fails and rolls back instead. Savepoint boundaries use weak keys so released savepoints are not
retained until transaction completion; normal cursor registration and removal remain constant time.

After Session completion, a retained cursor is closed and cannot expose a previously decoded row.
Always keep streams inside their Session/transaction and use try-with-resources for short circuits.

## Execution listeners

When a listener is registered, cursor and stream operations remain open until JDBC exhaustion,
explicit close or an actual failure. Exhaustion reports `EXHAUSTED`; explicit close before exhaustion
reports `CLOSED_EARLY`. Short-circuit consumption does not report completion until the resource is
closed. Thread ownership failures and abandoned resources do not manufacture completion events.
See [read-only execution listeners](execution-listeners.md) for IDs, row counts and failure boundaries.
