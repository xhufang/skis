# SQL expressions and semantic validation

This document describes the expression contract in the internal `0.2.10-SNAPSHOT` milestone. It accumulates toward the public `0.3.0` SQL DSL and
is not part of the published `0.2.0` API.

The current `Sql.value`, arithmetic, CASE, COALESCE, concat and CAST factories, aggregate result
matrix and GROUP BY/HAVING rules are documented in [aggregates and grouped results](aggregates-and-grouping.md).

## Query construction and execution

`executor.select(...).from(...).fetchList()` executes directly through `ExecutableSelectQuery<R>`.
`Sql.select(...).from(...).fetchList()` creates a `QueryRequest<List<R>>`; pass it to
`executor.run(request)` or `session.run(request)` to execute. `SelectQuery<R>` is immutable and reusable.
Constructing a query or a request does not acquire a connection. For example, with a generated table `pet`:

```java
var query = Sql.select(pet.id(), pet.name()).from(pet).where(pet.name().eq("Mimi"));
var rows = executor.run(query.fetchList());
var first = executor.run(query.fetchFirst());
```

The result is `Row2` with `first()`/`second()` fields; use `Row3` for three selections or generated Pro
for wider shapes. A query's `as(alias)` forms a derived source and `column(selectedExpression)` refers
to a unique selected output; give computed outputs an alias once. Either query entry can be embedded;
only the outer executor/Session and its execution options control the final SQL. Ordinary values are
captured directly, with no public parameter declaration/bind step. See [subqueries](subqueries.md),
[joins](joins.md) and [query-source writes](mutation-returning.md).

## Expression descriptors

Every `SqlExpression<T>` carries three independent pieces of metadata:

- the Java representation returned by `javaType()`;
- a portable `SqlType`, used before dialect rendering;
- explicit `Nullability`, propagated using SQL three-valued semantics.

Predicates always use `Boolean`/`BOOLEAN`. Ordinary comparisons become nullable when either
operand can be SQL `NULL`; `IS NULL` and `IS NOT NULL` are always non-null booleans. `COALESCE` is
non-null when at least one operand is non-null. An implicit `CASE` ELSE is SQL `NULL`.

## Portable expression nodes

The initial cross-dialect set contains:

- `ParameterSlot<T>` for final statement-level logical parameter positions;
- allow-listed `LiteralExpression<T>` values: `NULL`, `TRUE`, `FALSE`, numeric `0`, and numeric `1`;
- `ArithmeticExpression<T>` with add, subtract, multiply, and divide;
- `ConcatExpression` using SQL-standard character concatenation;
- searched `CaseExpression<T>` and `CaseWhen<T>` branches;
- `CastExpression<T>` for the portable PostgreSQL/H2 target subset;
- `CoalesceExpression<T>` with two or more compatible operands.

`IncrementExpression<T>` remains as the specialized version-column `+ 1` node used by the
mutation Fast Path. General arithmetic should use `ArithmeticExpression<T>`.

## Parameter value capture

Ordinary values remain outside the AST and shared plan key. `.eq(value)`, `.between(...)`,
`.like(...)`, membership elements and `Sql.value(value)` capture values when expressions are built.
Both static and executable query builders use the same immutable value environment; there is no
public QueryParameter, Sql.parameter or bind step. To supply different values, build a new query
or use a Java factory that captures those values.

Internal logical identities are scoped to query-block occurrences. Reusing one expression within
a block reuses its logical identity; embedding a child twice creates independently analyzed
occurrences. Final statement layout assigns dense logical ParameterSlot ordinals and records each
physical JDBC `?` in SQL order. Content/count/lowered statements use their own final retained layout.
The same property Codec source is required when a parameter identity is reused in a block.

Capture recursively copies arrays and clones mutable java.sql.Date/Time/Timestamp values once.
Composing environments, laying out slots, binding and reading rows do not repeat that copy.
Other built-in values are immutable. Custom predicate Codecs must use deeply immutable values and
must not mutate bound input; the framework cannot infer a general safe copy.

## Reusable SELECT and embedding

`Sql.select(...)`/`Sql.selectFrom(...)` construct SelectQuery<R>; terminals construct typed requests.
The corresponding executor/Session builders produce ExecutableSelectQuery<R> and execute directly.
Both are immutable, retain SQL structure separately from captured values, and can be embedded as
SelectSource<R>. Embedded execution context and options do not propagate to the outer SQL.

```java
var petIds = Sql.select(pet.id()).from(pet)
    .where(pet.name().eq("Mimi")).orderBy(pet.id().asc());
List<Long> ids = executor.run(petIds.fetchList());
```

SingleColumnQuery<V> proves one visible SQL value per row, not one-row cardinality;
SingleColumnSelect<V> and ExecutableSingleColumnSelect<V> implement that common contract.
EXISTS/NOT EXISTS accept either entry's supported result shape. IN/NOT IN require the appropriate
single-column Java/SQL type and preserve SQL three-valued logic. Scalar embedding preserves the
selected type and Codec, with nullable zero-row semantics and a database error for multiple rows.
Use `Sql.scalar(query)` when an explicit expression is useful, or pass the query directly to
supported comparisons and SET. No child query, decoder or JDBC resource owner is created.

`query.as(alias)` forms a derived relation. `source.column(selectedExpression)` refers to a unique
selected output; computed outputs need an alias once. The framework derives type/Codec/occurrence
and effective nullability; an unselected or ambiguous expression is rejected. Ordinary derived
sources cannot correlate to outer/sibling sources. See [subqueries](subqueries.md).

`BigInteger` division is rejected because both baseline databases implement it through SQL
`DECIMAL` division, whose result may have a fractional part that cannot be decoded exactly as a
`BigInteger`. Cast both operands to `BigDecimal` before division when fractional results are
required. Addition, subtraction, and multiplication retain `BigInteger` result semantics.

Portable CAST targets cover boolean, numeric, character, UUID, date, time, and timestamp types.
`TINYINT` is rendered as the PostgreSQL/H2 common `SMALLINT` target. `VARBINARY` and `OTHER` are
not exposed as portable CAST targets because the two baseline dialects do not share a safe type
name and conversion contract for them.

Application data must never be encoded as a literal. `LiteralExpression` has no arbitrary string
or value constructor; application values remain outside AST equality and enter SQL through a
query-level reference, final `ParameterSlot`, and JDBC binding. The following low-level final AST
contains five logical slots even when the values change between executions:

```java
SelectStatement statement =
    new SelectStatement(
        List.of(
            new ArithmeticExpression<>(pet.id(), ArithmeticOperator.ADD, idOffset),
            new ConcatExpression(List.of(pet.name(), suffix)),
            new CaseExpression<>(
                List.of(new CaseWhen<>(pet.id().gt(threshold), pet.name())),
                otherwiseName),
            new CastExpression<>(pet.id(), String.class),
            new CoalesceExpression<>(List.of(pet.name(), fallbackName))),
        pet);
```

The corresponding PostgreSQL/H2 shape is:

```sql
SELECT ("pet"."id" + ?),
       ("pet"."pet_name" || ?),
       CASE WHEN "pet"."id" > ? THEN "pet"."pet_name" ELSE ? END,
       CAST("pet"."id" AS VARCHAR),
       COALESCE("pet"."pet_name", ?)
FROM "shelter"."pet"
```

## Central validation boundary

`SemanticValidator` is the single rule owner for current portable expression semantics. Node
constructors delegate their local compatibility checks to it. Statement construction checks local
shape, while complete query compilation and direct rendering add scope and parameter-shape checks;
mutation statements continue to validate their complete target scope during construction.

Before rendering, validation rejects:

- incompatible Java or SQL types for comparisons, ranges, membership, arithmetic, CASE, and
  COALESCE;
- `BigInteger` division whose possible fractional SQL result cannot satisfy its Java result type;
- ordered operators on non-orderable types and `LIKE`/concatenation on non-character types;
- ordinary comparison with an expression that is statically known to be SQL `NULL`;
- a column from another table expression or alias, including references nested inside CASE,
  arithmetic, concatenation, casts, and COALESCE;
- conflicting descriptors for a repeated parameter ordinal, or a gap in zero-based ordinals;
- columns outside the mutation target, writes to read-only entities, non-insertable/non-updatable
  columns, and nullable assignments to non-null columns.

Custom `SqlExpression` implementations are not a portable expression extension contract. Because
the AST does not define a public child-traversal SPI, unknown nodes fail during semantic validation
instead of being treated as value-only leaves. A future traversal SPI would require a separate
architecture decision.

INSERT VALUES has no visible table-column scope. UPDATE FROM, DELETE USING and MERGE expressions
resolve the target and explicitly declared sources; nested SELECTs retain their own correlation scope.
SELECT uses an ordered FromClause: the root and each completed Join occurrence form its visible scope.

The current AST routes the FROM root, Join right-hand sides and occurrences through sealed
`RelationSource` nodes. Entity sources retain the original `TableExpression<?>` reference;
derived sources contain a SELECT with an explicit ordered output shape. CTEs remain assigned to
the `0.3.x` development line toward public `0.4.0`.

SELECT validation has two boundaries. Construction enforces context-free local invariants,
traverses every expression to validate its Java/SQL/nullability descriptor, rejects conflicting
descriptors for a repeated parameter ordinal within the query block, and defensively freezes all
lists, including the ordered `groupBy` structure. Complete scope, effective-nullability,
and statement-wide dense parameter-layout checks run through
`SemanticValidator.validateComplete(...)` after the query block has its embedding context. Query
plan compilation and `SqlCompiler` enter the controlled `ResolvedStatement` / target-bound
`RenderInput` pipeline before SQL output. Renderers consume only prepared inputs. This lets reusable
fragments defer parent-dependent checks while every execution/direct-compilation path retains
semantic validation, dialect checks, lowering and post-validation. GROUP BY/HAVING and aggregate
ownership are validated per query block; unsupported dialect capabilities fail before rendering.

## SELECT ordering, pagination and count

The `0.2.3` SELECT structure adds visible and hidden selections, `distinct`, ordered expressions,
and parameterized `Limit`, `OffsetLimit` or `KeysetSeek` pagination. `CountAst` is independent: it
contains only its source, predicate and optional distinct expression, so count rendering cannot be
implemented by deleting fragments from a content SQL string. A nullable distinct expression adds a
null-presence term because `SELECT DISTINCT` returns one `NULL` row while SQL `COUNT(DISTINCT ...)`
does not count `NULL`.

Structural equality includes direction, null placement, hidden selections and pagination parameter
descriptors, but not bound limit, offset, predicate or keyset values. Repeated parameter ordinals
are legal when their Java type, SQL type and nullability descriptors agree; this permits a typed
keyset anchor to appear in multiple branches of a lexicographic seek predicate.

`SemanticValidator` checks that order and hidden expressions belong to the completed Join scope,
pagination slots use the required non-null integer/long descriptors, pagination has a stable
order unless the final offset result is proven to have at most one row, and all parameter ordinals
remain dense. Dialect preparation then requires explicit
parameterized limit/offset capabilities and either uses native null ordering or a semantically
equivalent `CASE` fallback.

## Join scope and generated result rows

The `0.2.4` query structure replaces the earlier single-table scope with an ordered `FromClause`.
The root is occurrence 0 and joined tables receive dense occurrence ordinals. Aliases are distinct
table-expression objects, including two aliases of the same entity. ON, WHERE, ordering, visible
selections, and hidden pagination selections are all validated against this final scope.

`@ReusableProjection` now describes only how to construct one result row. Its generated companion
has
a fixed-arity, typed `of(...)` method:

```java
@ReusableProjection
public record PetOwnerView(Long petId, @Nullable String ownerName) {}

executor
    .select(PetOwnerViewPro.of(pet.id(), owner.name()))
    .from(pet)
    .leftJoin(owner)
    .on(pet.ownerId().eq(owner.id()))
    .fetchList();
```

The companion binds a query-independent `ProjectionMapping` to the supplied `Selectable` columns.
After every Join is known, query compilation resolves each column to an occurrence and canonical
codec, checks exact boxed Java type, portable SQL compatibility, and effective nullability, then
builds a decoder with fixed one-based ResultSet indexes. Row decoding performs no reflection,
column-name matching, `getObject` guessing, registry lookup, or per-row codec lookup.

The complete Join contract—including Join forms, aliases, staged ON visibility, nullable entity
presence, duplicate rows, pagination stability, count semantics, and dialect support—is documented
in [Explicit joins and generated result rows](joins.md).

## Query-block identity and ancestor scope

The `0.2.5-SNAPSHOT` scope-analysis foundation assigns every SELECT block a `QueryBlockPath`. The
root is `$`; a nested block appends the parent clause, clause item ordinal, and deterministic nested
ordinal. A resolved physical column is identified by that block path, its source occurrence ordinal,
and canonical property metadata. A resolved parameter is identified by block path plus its current
AST slot ordinal and type descriptor; final nested statement layout may later relocate that slot.
Neither identity contains a JVM object address or ordinary parameter value.

The internal `SemanticValidator.analyzeComplete(statement)` computes immutable query-block facts
for the controlled `ResolvedStatement.analyze(statement)` entry. Complete analysis remains internal;
dialect extensions receive only the necessary occurrence information. These facts include:

- final ordered source occurrences and their outer-Join null-extension state;
- each clause expression's exact position, resolved column/parameter dependencies, effective
  nullability, and value-independent `ResolvedStructureKey`;
- clause-specific scope snapshots used to analyze future nested SELECT nodes.

Analysis is fail-closed for expression kinds. Although `SqlExpression` remains a low-level public
interface, an implementation unknown to the framework is rejected rather
than recorded as an opaque leaf. Each framework-owned node must explicitly expose its children to
local semantic validation, effective-nullability resolution, scope/dependency analysis, and
rendering before it can participate in a query.

The reusable `SelectStatement` remains unchanged. Analyzing the same child at two embedding
locations creates two results and two paths; no resolved ancestor target, nullability, or validation
flag is written back to the child AST.

Scope snapshots are clause-sensitive. A Join ON snapshot contains the accumulated left side and the
current right occurrence, before the current Join applies null extension. Final SELECT, WHERE,
GROUP BY, HAVING, ORDER BY, and pagination snapshots contain the completed Join state. A reference
to a later Join occurrence therefore fails even if that source exists in the eventual FROM clause.

An unresolved nested column may bind only to an object-identical table expression in the visible
ancestor chain. A structurally equal table or same-name alias object cannot substitute for it;
siblings never enter each other's chain. Registering the exact same table object in both a child and
a visible ancestor is ambiguous and fails with guidance to create an independent alias. Equal
qualifiers in separate blocks are otherwise legal, but correlation fails when a nearer source's
effective qualifier would make rendered SQL bind to that nearer source instead of the intended
ancestor. Because PostgreSQL truncates identifiers beyond its default 63-byte limit even when they
are quoted, complete scope analysis rejects effective qualifiers longer than 63 UTF-8 bytes and asks
the caller to provide a shorter alias. Within that portable boundary current PostgreSQL/H2
identifiers are always quoted, so qualifier collision checks compare the final raw value exactly;
another identifier rule must provide its corresponding normalized collision semantics when
introduced.

Ordinary derived FROM/Join relation children use a non-correlated boundary. Their child analysis
retains a stable path but receives no parent scope, preventing an accidental LATERAL contract.
EXISTS, IN,
and scalar-subquery AST nodes use this shared machinery and record every child occurrence at its
exact clause location. The scalar result is conservatively nullable even when the child's selected
column is non-null. Derived columns resolve by concrete source occurrence plus public output
ordinal, and their published nullability can be extended again by an outer Join.

The public `ExistsPredicate` owns a complete `SelectStatement` subtree and a native negation flag.
Its Boolean result is always non-null. Recursive validation preserves child selections, NULLs,
duplicates, ordering, and conditions; it neither injects a limit nor rewrites the child as a Join.
The outer renderer shares its SQL buffer and parameter list with nested render contexts while each
context resolves local columns before walking its validated ancestor chain.

Compilation runs complete semantic validation before dialect checks, lowering, post-validation and
rendering. Renderers consume target-bound RenderInput; no direct renderer bypass accepts arbitrary
statement/analysis pairs. Query-block scope is enforced before serialization.
