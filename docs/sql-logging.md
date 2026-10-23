# SQL logging and parameter redaction

The current snapshot uses SLF4J 2.0.20 API. Applications choose their logging provider; SKIS does
not supply a provider, bridge or backend. SQL logging is explicitly configured on the executor:

```java
import io.skis.core.SqlLoggingOptions;
import io.skis.runtime.Skis;
import java.time.Duration;

var logging = SqlLoggingOptions.builder()
    .sql(true)
    .slowQueryThreshold(Duration.ofMillis(250))
    .parameters(true)
    .build();

var executor = Skis.builder(dataSource)
    .dialect(dialect)
    .sqlLogging(logging)
    .build();
```

`dataSource` and `dialect` are the application's existing JDBC source and selected dialect.
Executors capture immutable configuration; transaction sessions inherit it. SQL lifecycle logging,
slow-query logging and parameter summaries are disabled by default. Slow logging requires an explicit
positive threshold; there is no hidden threshold.

| Logger | Level | Additional requirement | Output |
| --- | --- | --- | --- |
| `io.skis.sql` | DEBUG | `sql(true)` | Value-free SQL start/completion and controlled SQL templates |
| `io.skis.sql.slow` | WARN | Positive `slowQueryThreshold(...)` | Statement completion and a bounded SQL template when lifecycle duration reaches the threshold |
| `io.skis.sql.parameters` | DEBUG | `parameters(true)` | One bounded summary after the SQL's parameters bind successfully |
| `io.skis.internal` | WARN | Internal listener failure or abandoned cursor | Safe categories and identifiers, without a Throwable or driver message |

SQL and slow logs use the existing [execution listener](execution-listeners.md) lifecycle. The
threshold measures the statement lifecycle, including JDBC binding, reading and owned statement cleanup.
Listener callbacks and synchronous parameter redaction, summary formatting and output time are excluded
from both statement and operation durations. Summary timing runs only when summaries and statement
observation are both active; JDBC binding and execute time remain included. Cursors complete on
exhaustion, close or failure. Parameters-only logging
does not activate operation events, IDs or timers. User listeners continue to run when logging is off.
An operation captures each configured SQL/slow logger's level once at its start, preserving its
lifecycle if levels change later. Query and mutation entries use that same capture to decide whether
observation is needed; no listeners and no logging demand return before events, IDs or timers.
The immutable logging/dispatcher combinations are prepared at executor assembly and reused; selecting
an operation's demand does not allocate another logging adapter or dispatcher.
Slow logging includes the controlled SQL template even when ordinary SQL logging is disabled.
The template is escaped and limited to 4096 Unicode code points, and is formatted only for a slow statement.

## Parameter policy

The default `ParameterRedactor.TYPE_ONLY` reports the declared Java type for each final, one-based
JDBC slot. It does not format the value, traverse collections/arrays, or consume a stream/LOB.
To supply application-approved text, register a thread-safe synchronous policy:

```java
var logging = SqlLoggingOptions.builder()
    .parameters(true)
    .parameterRedactor((jdbcIndex, declaredJavaType, value) -> "[redacted]")
    .build();
```

Registering a policy alone does not enable summaries. A custom policy is trusted to choose safe
output: escaping and length limits cannot remove a secret deliberately returned by that policy.
Do not retain or modify `value`, consume one-shot inputs, access JDBC, or reenter SKIS from the policy.

The executor selects the normal or observed binder once per SQL using the summary switch and
DEBUG level. Framework and APT binders synchronously reuse their existing binding locals, including
initialized insert versions. They do not repeat entity getters, copy parameters for logging, rerender
SQL or inspect result rows. Repeated/reordered slots and pagination use the final SQL layout;
Page content/count have independent summaries. The fingerprint identifies the SQL template.

| Limit | Budget |
| --- | --- |
| One value | 128 Unicode code points after control-character escaping |
| One SQL summary, including labels and markers | At most 4096 Unicode code points |
| Included slots | At most 64; the total text budget can stop output earlier |

Truncation preserves surrogate pairs and reports a bounded omitted-slot count. Once the budget is
reached, framework and generated binders stop observing further values, including primitive boxing
for callbacks, while still binding every remaining JDBC slot. `ParameterBindingObserver.needsMore()`
defaults to true for observers without a budget; once false, a binder can stop polling it for that SQL.
The omitted count comes from the complete plan layout, so a deliberately stopped summary remains
truncated rather than becoming unavailable. A policy exception or null result becomes
`[redaction-failed]`; the SPI therefore declares a nullable String result. An opaque custom
`ParameterBinder` uses `[unavailable]`; it runs once and is
never replayed, reflected over or intercepted with a JDBC proxy. Custom binders may implement the
supported `bindObserved(..., ParameterBindingObserver)` contract to expose existing locals
synchronously. This generated binding contract requires ABI 8 and matching processor/runtime.

Incomplete/failed binding produces no summary. Raw values live only on the synchronous binding
stack; the per-SQL accumulator holds escaped strings and is not attached to a plan, event,
diagnostic or global state. Asynchronous backends receive only finished strings.
When a statement observation exists, the summary reuses its SQL fingerprint and includes the existing
`operation` and `statement` IDs used by SQL lifecycle logs. This distinguishes concurrent attempts of
the same SQL and associates Page content/count with their own statement events. The accumulator
receives only primitive IDs and does not retain the observation. Parameters-only logging omits these
IDs and computes the fingerprint only after a complete successful binding; disabled summaries do no extra hashing.

## Failures and verification

Ordinary policy/output failures do not change execution or cleanup results and are not recursively
logged. Default logs do not expand original exceptions; original cause/suppressed chains remain
available for explicit diagnosis. Fatal `Error`s propagate through the existing resource cleanup
contract. An abandoned-cursor warning does not move JDBC resource ownership to the Cleaner thread.
Both synchronous log output and DEBUG/WARN level probes share the listener re-entry guard: backends
cannot enter SKIS compilation or execution while handling either call, including through another
executor. Nested probes and safety logs preserve an enclosing listener's guard, and normal or fatal
backend exits release only the scope owned by that call. Ordinary probe failures disable that output;
fatal probe errors propagate through the existing cleanup contract.
The guard reuses a thread-local Boolean and does not update a shared atomic counter on each probe,
output or listener callback. Before its first use, a monotonic gate skips thread-local access;
afterward each thread initializes its entry on first access and reuses it, holding no invocation data.
If completion logging raises an `Error` after a JDBC failure, the propagated error retains that failure
and its existing cause/cleanup chain as suppressed; connection release is still attempted.

The 0.2.8 implementation passed [CI](https://github.com/xhufang/skis/actions/runs/39957930561)
at commit `9dbb932`: Java 21/25 logging, binding, resource and concurrency contracts, real PostgreSQL
regression, independent consumers and the coverage ratchet passed. `SqlLoggingTest` executed 15 tests,
`ParameterLoggingTest` 20, and the independent H2 `SqlLoggingConsumerTest` 5, with zero failures, errors
or skips. No local compilation or execution was performed.
