# Read-only execution listeners

Register a thread-safe `io.skis.core.spi.ExecutionListener` when assembling an executor:

```java
ExecutionListener listener = new ExecutionListener() {
  @Override
  public void statementCompleted(StatementCompletedEvent event) {
    metrics.record(event.dialectId(), event.purpose(), event.durationNanos());
  }
};

SkisExecutor executor = Skis.builder(dataSource)
    .dialect(H2Dialect.INSTANCE)
    .listener(listener)
    .build();
```

`metrics` in this example is the application's thread-safe metrics sink. Listener implementations
need only `skis-core`; they do not need JDBC, query, runtime or dialect implementation types. The
executor builder takes an immutable snapshot of registrations. Sessions inherit that same snapshot,
including duplicate registrations. Subsequent builder changes affect later executors.

## Callbacks and correlation

All five methods have default empty implementations:

| Callback | Meaning |
| --- | --- |
| `operationStarted` | One terminal query or mutation invocation begins. |
| `compilationCompleted` | A plan was actually compiled, or its preparation failed. |
| `statementStarted` | One SQL attempt begins after connection acquisition. |
| `statementCompleted` | The SQL attempt and its owned statement resources have terminated. |
| `operationCompleted` | The invocation and its owned cleanup have terminated. |

An operation ID correlates a terminal invocation with its statements and on-demand compilations.
Statement IDs distinguish individual SQL attempts within that invocation. IDs belong to one executor
and its sessions; they are not global trace IDs. A Page has one operation and separate `PAGE_CONTENT`
and `PAGE_COUNT` statements. Compile order need not match SQL execution order.

`CompilationOrigin.PREWARM` uses `CompilationCompletedEvent.NO_OPERATION_ID` (`0L`) and does not
invent an operation lifecycle. Cache hits and threads waiting for another compilation do not emit
duplicate compilation events. Independent `SqlCompiler.compile(...)` calls emit no listener events.

## Completion and resource ownership

Materialized reads and writes finish with `COMPLETED`. Cursors and streams finish with `EXHAUSTED`
when JDBC exhaustion is observed, or `CLOSED_EARLY` when explicitly closed before that point. An
actual execution or cleanup failure finishes with `FAILED`.

Keep streams and cursors in try-with-resources. A short-circuit stream operation does not finish the
resource lifecycle until close. User callback exceptions, thread ownership violations and abandoned
resources do not manufacture successful completion or database failures. Existing resource close
order and primary/suppressed exception ownership still apply; see [cursor and stream ownership](cursor-and-stream.md).

`readRowCount` counts rows actually read from JDBC, including a Slice's extra probe row. A count
statement normally reads one row; the count value is not its row-read count. `affectedRowCount`
contains the driver's reported write count. Counts that are unknown or inapplicable use
`StatementCompletedEvent.UNKNOWN_ROW_COUNT` (`-1L`). An execute call that was not reached uses
`UNKNOWN_DURATION_NANOS` (`-1L`). Operation and statement durations exclude listener callback time.

## Safety and callback behavior

Callbacks execute synchronously in registration order on the invoking thread. Different invocations
can call the same listener concurrently, so listeners must synchronize their own state. Listeners
cannot change SQL, parameters, results or resources.

Events contain immutable structural snapshots, parameterized SQL templates, fingerprints and safe
framework diagnostics. They expose no parameter/result values, original Throwable, AST, Connection,
Statement or ResultSet. Preserve that boundary in application logs and metrics.

An ordinary listener `Exception` is isolated and later listeners still run. The safety log contains
only callback, listener class and exception class; it does not render the exception message or cause.
An `Error` propagates after the applicable cleanup. Synchronous re-entry into SKIS compilation or
execution is rejected with `IllegalStateException`, including entry through a different executor or
independent compiler. This rejection is a listener usage failure.

Executors with no listeners use the unobserved path without per-invocation observation objects,
events, IDs or additional observation wrappers. This is a structural contract, not a measured
throughput or allocation claim.

## Independent consumer contracts

The [consumer fixtures](../skis-integration-tests/src/consumer/README.md) exercise core-only listener
compilation, actual H2 registration and lifecycles, and PostgreSQL driver behavior outside the
repository reactor. PostgreSQL is supplied by the CI service; these fixtures do not install a local
database. Results for a changed fixture require that revision's CI evidence.
