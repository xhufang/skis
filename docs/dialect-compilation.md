# Dialect SPI and controlled compilation

This guide describes `0.2.8-SNAPSHOT`, an internal milestone whose capabilities accumulate toward
public `0.3.0`. The latest public release remains `0.2.0`.

## Dependencies and boundaries

An external dialect implements `io.skis.dialect.spi.Dialect` from `skis-dialect-api`. The shared
immutable SQL model lives in `skis-sql-ast`. Standard rendering, identifier rules and the safe
`SqlCompiler` facade live in `skis-dialect-support`, which is an optional reuse dependency for
dialect authors. PostgreSQL and H2 are the current supported dialects; a test-side MySQL syntax
fixture does not establish MySQL support.

Applications use `Skis`, `Sql`, generated tables and projection companions. They do not construct
internal query analysis, JDBC plans or runtime registries. Generated code and supported external
extensions do not import `*.internal`. T03 moved QueryPlanCatalog and QueryRuntime into
io.skis.query.internal and removed the root-package assembly exceptions. The public DSL keeps
business interfaces and generated-table construction contracts. Architecture checks are wired
but await current CI evidence; package naming alone is not a strict JPMS export boundary.

## Stage order

The shared query/mutation and database-free compilation pipeline follows this order:

1. Analyze the immutable statement with `ResolvedStatement.analyze(statement)`.
2. Validate recursive dialect capabilities and applicable custom dialect rules.
3. Run dialect lowering, then portable normalization.
4. Validate the final result shape, scope, types and logical parameter identities.
5. Create the final statement's parameter layout.
6. Render the target-bound `RenderInput` into SQL.

`ResolvedStatement` and `RenderInput` have controlled construction. Dialects inspect only the
necessary resolution information for an occurrence in that exact stage. A matching structural key
does not authorize exchanging analysis between different statement instances or target contexts.
Identity lowering still receives post-validation; a changed structure is analyzed again.

Renderers accept only `RenderInput` for their configured target. To generate SQL from raw AST,
use the support facade:

```java
SqlTemplate template = SqlCompiler.compile(statement, dialect);
String sql = template.sql();
ParameterLayout layout = template.parameterLayout();
```

The facade accepts either `StatementAst` or an existing `ResolvedStatement`. It returns SQL and a
value-independent layout, acquires no connection and runs no JDBC operation. Independent static
compilation emits no executor listener events, while calls inside a listener callback still fail
the synchronous re-entry guard.

NULL-order emulation, nullable DISTINCT-count compensation, selected-position ordering references
and empty IN/NOT IN normalization occur before final layout. The renderer serializes their
accepted structures. User aggregate DSL, aggregate type rules and GROUP BY/HAVING remain assigned
to `0.2.9`; existing pagination count SQL does not expose those capabilities.

## Identifiers, cache identity and values

DSL identifiers currently match ASCII `[A-Za-z_][A-Za-z0-9_]*`. Database-specific identifier
length limits, including byte-based limits, are not validated. Quoting and quote escaping do not
enable Unicode DSL identifiers. Unicode and database-specific length capabilities belong to the
formal dialect milestones (`0.4.x` toward `0.5.0`); there is no universal length limit in this snapshot.

PostgreSQL and H2 quote each identifier component and preserve its declared case. Schemas created
with unquoted names can therefore differ from quoted metadata, especially with H2's default case
folding. Use schema/table/column spellings consistently in metadata and DDL. Unsupported catalog
qualification is rejected before rendering. H2 compatibility mode does not replace real PostgreSQL
driver verification.

Dialect ID, complete capabilities and behavior version form part of shared plan identity. A custom
dialect defaults to bypassing shared L1 caching. Opt into `hasStablePlanCacheIdentity()` only when
all SQL-affecting configuration, validation, lowering and rendering behavior is stable and
thread-safe. Unknown parameter limits do not mean unbounded limits.

Ordinary parameter values, execution contexts, stage objects, invocation ASTs and listener events
do not enter shared plans. Final SQL statements have independent layouts, including content/count
and lowered variants. Cache hits and threads waiting for another compilation do not emit duplicate
compilation notifications. See [execution listeners](execution-listeners.md).

## Verification

The [independent consumers](../skis-integration-tests/src/consumer/README.md) exercise both a
dialect-api-only implementation and support-module reuse without framework internal imports.
The T09 merge passed [CI](https://github.com/xhufang/skis/actions/runs/39466901460), including
Java 21/25, PostgreSQL 16, H2, controlled-stage tests and independent consumers. Later source
changes require their own validation evidence.
