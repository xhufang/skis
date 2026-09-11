# SQL expressions and semantic validation

This document describes the expression contract implemented by the internal `0.2.2-SNAPSHOT`
through the current `0.2.5-SNAPSHOT` milestone. It accumulates toward the public `0.3.0` SQL DSL and
is not part of the published `0.2.0` API.

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

Ordinary values remain outside the AST and plan cache key, but an immutable query must also own a
stable value snapshot. The query layer now separates parameter identity into three levels:

1. `QueryParameter<V>` is an opaque, strongly typed reference with no value, query-block ordinal,
   logical ordinal, or JDBC position. References use object identity; an optional name is diagnostic
   only.
2. Immutable `QueryParameters` associates references with captured values. Missing, extra,
   duplicate, nullability-invalid, and runtime Java-type-invalid bindings fail before JDBC work.
3. Final statement layout assigns dense zero-based `ParameterSlot<T>` ordinals. The renderer records
   a slot once for every physical `?`, so one logical reference may occur at several JDBC positions.

Reference reuse is scoped to one query-block occurrence. Repeating a reference inside one block
reuses a logical slot; embedding the same parameterized block twice creates two dense statement
slots that read the same captured binding. This keeps later nested-query occurrence identity
separate from parameter names and object addresses in structural fingerprints.

The complete reusable query description validates missing and extra bindings once. Each final
statement then projects only the references it retained, so count construction or dialect lowering
may discard parameterized expressions without turning their otherwise valid bindings into false
"extra binding" failures.

Each logical slot resolves its JDBC binder from the owning property's runtime `JdbcTypeCodec`.
Parameter nullability—not physical column mutation nullability—controls whether a query value may be
`null`; a nullable search parameter can therefore bind SQL `NULL` even when compared with a non-null
column. Reusing one reference inside a query-block occurrence requires the same canonical property
Codec source, preventing one column's custom Codec from silently binding another column's use.

`Sql.parameter(Class<V>)` creates an explicit reusable reference. Existing `.eq(value)`,
`.between(...)`, `.like(...)`, and `IN`/`NOT IN` conveniences lower to anonymous references plus an
internal parameter environment, so their source API and SQL semantics do not change.

Binding and predicate construction copy every array value recursively and clone the built-in
mutable JDBC representations `java.sql.Date`, `Time`, and `Timestamp`. Comparison and between bounds
as well as every `IN`/`NOT IN` element use this same `QueryParameters` capture boundary. Copying
happens once, never while composing environments, laying out slots, binding JDBC parameters, or
reading rows.

Other supported Java values are immutable and remain allocation-free at capture. A custom
`JdbcTypeCodec` whose values participate in predicates must use a deeply immutable value type, and
its `bind` implementation must not mutate the supplied value; SKIS cannot infer a safe copy for an
arbitrary custom object.

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

INSERT values have no visible table-column scope. UPDATE expressions and predicates may reference
only the target table expression. DELETE predicates may reference only the target. SELECT uses an
ordered `FromClause`: the root and each completed Join occurrence form its visible scope.

Subqueries, derived tables, joins, and CTEs were not represented by the `0.2.2` AST. Explicit joins
were added by the `0.2.4` scope described below. The `0.2.5` AST now routes the FROM root, Join
right-hand sides, and occurrences through sealed `RelationSource` nodes; the first slice provides
only the entity adapter and retains the original `TableExpression<?>` reference. Derived and
subquery source nodes remain deferred to their later capability slices, and CTEs remain deferred to
0.2.6.

SELECT validation has two boundaries. Construction enforces context-free local invariants,
traverses every expression to validate its Java/SQL/nullability descriptor, rejects conflicting
descriptors for a repeated parameter ordinal within the query block, and defensively freezes all
lists, including the reserved ordered `groupBy` structure. Complete scope, effective-nullability,
and statement-wide dense parameter-layout checks run through
`SemanticValidator.validateComplete(...)` after the query block has its embedding context. Query
plan compilation and every built-in Renderer invoke this complete boundary before SQL output, so a
reusable fragment can defer parent-dependent checks without allowing a direct AST rendering path to
bypass validation. The optional `having` and ordered `groupBy` containers are structural only in
this first slice; renderers reject them until the aggregate/grouping capability slice enables SQL
generation.

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
pagination slots use the required non-null integer/long descriptors, offset/keyset pagination has
an order, and all parameter ordinals remain dense. Dialect rendering then requires explicit
parameterized limit/offset capabilities and either uses native null ordering or a semantically
equivalent `CASE` fallback.

## Join scope and generated result rows

The `0.2.4` query structure replaces the earlier single-table scope with an ordered `FromClause`.
The root is occurrence 0 and joined tables receive dense occurrence ordinals. Aliases are distinct
table-expression objects, including two aliases of the same entity. ON, WHERE, ordering, visible
selections, and hidden pagination selections are all validated against this final scope.

`@SkisProjection` now describes only how to construct one result row. Its generated companion has
a fixed-arity, typed `of(...)` method:

```java
@SkisProjection
public record PetOwnerView(Long petId, @Nullable String ownerName) {}

executor
    .select(PetOwnerViewProjection.of(pet.id(), owner.name()))
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

`SemanticValidator.analyzeComplete(statement)` returns an immutable `QueryBlockAnalysis` containing:

- final ordered source occurrences and their outer-Join null-extension state;
- each clause expression's exact position, resolved column/parameter dependencies, effective
  nullability, and value-independent `ResolvedStructureKey`;
- clause-specific scope snapshots used to analyze future nested SELECT nodes.

Analysis is fail-closed for expression kinds. Although `SqlExpression` remains a low-level public
interface for source compatibility, an implementation unknown to the framework is rejected rather
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

Ordinary FROM/Join relation children use a non-correlated boundary. Their child analysis retains a
stable path but receives no parent scope, preventing an accidental LATERAL contract. EXISTS, IN,
scalar-subquery, and derived-source AST nodes are added by their later 0.2.5 slices; this step only
establishes the identity, scope, dependency, and failure machinery those nodes share.

Executable query compilation runs complete semantic validation before dialect capability checks and
SQL rendering. Renderers also perform the same validation defensively when called directly; the
compiler does not rely on a third-party renderer to enforce query-block scope.
