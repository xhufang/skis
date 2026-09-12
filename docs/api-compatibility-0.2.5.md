# 0.2.5 API compatibility ledger

This ledger records the public API differences approved for steps 1, 3, 4, and 5 of the internal
`0.2.5-SNAPSHOT` milestone. It is not the consolidated compatibility report for the unfinished
`0.2.x` development line. The root `pom.xml` japicmp allow-list names each approved class or
method so unrelated public API breaks continue to fail compatibility checks.

## Step 1: relation-source AST

## Added API

- Sealed, framework-owned `RelationSource` and its entity adapter `EntityRelationSource`.
- `FromClause` and `JoinClause` constructor overloads accepting relation sources.
- Ordered `SelectStatement#groupBy()` and optional `SelectStatement#having()` structure, plus
  matching constructors. These nodes are reserved for later 0.2.5 aggregate DSL and dialect
  slices; current renderers reject non-empty grouping structure instead of dropping it.
- `SemanticValidator#validateComplete(...)` as the explicit validation boundary for a statement
  embedded in its complete query-block context.
- `SqlRenderer` implementations exposed for direct AST rendering are now required to invoke that
  complete validation boundary before emitting SQL.

## Changed AST accessors

- `FromClause#root()` now returns `RelationSource` instead of `TableExpression<?>`.
- `JoinClause#right()` now returns `RelationSource` instead of `TableExpression<?>`.
- `TableOccurrence` now stores a `RelationSource`; entity-backed callers can inspect
  `entityTable()` without manufacturing entity metadata for future non-entity sources.

Existing `FromClause`, `JoinClause`, `SelectStatement`, and `CountAst` construction overloads that
accept `TableExpression<?>` remain available. They create entity adapter nodes internally and
retain the exact original table-expression reference used by identity-based scope resolution. The
business query DSL therefore requires no source changes for this step.

## Validation timing

`SelectStatement` construction now enforces only context-free local invariants. Scope,
effective-nullability, and complete parameter-layout checks run through
`SemanticValidator.validateComplete(...)`. The query plan compiler and built-in renderers call
that boundary before SQL rendering, so directly supplied AST cannot bypass semantic validation.

Code that directly constructed an intentionally incomplete SELECT and expected an immediate scope
failure must call the complete validator or render/compile the statement instead. This timing
change is required so a reusable correlated fragment can be constructed before its parent query
context exists; it does not move failures past the JDBC boundary.

## Step 3: shared selectable expressions and result mappings

### Added API

- `Selectable<V>` now owns the shared comparison, null-test, range, membership, and ordering
  operations used by physical columns and future framework expressions.
- `QueryOperations` accepts `Selectable<V>` and `NonNullSelectable<V>` directly for scalar
  selection, including the explicit nullable-result entry point.

The framework-owned `ExpressionSelectable<V>`, `ResolvedValueMapping<V>`, and their supporting
types are package-private implementation infrastructure, not public extension points.

### Approved removals and replacements

- Entity-parameterized `QueryPredicate<E>` is removed. WHERE, ON, and future HAVING conditions use
  the root-neutral `QueryCondition`; `SelectQuery` and `NullableSelectQuery` no longer duplicate
  entity-narrow condition overloads.
- `ProjectionSelectFromStep<R>` is removed. Entity, scalar, and generated projection selections all
  use `SelectFromStep<R>`, whose generic `from(QueryTable<F>)` method independently infers the real
  entity root.
- `SortSpecification<E>` becomes the non-generic `SortSpecification`, which stores a selectable
  expression instead of an entity-bound property ordinal.
- Column-specific `QueryOperations#select(...)` and `selectNullable(...)` signatures are replaced
  by the selectable-based signatures. Generated query columns implement the corresponding sealed
  selectable contract, so ordinary call expressions remain source-compatible.

### Migration

- Replace declarations of `QueryPredicate<Entity>` with `QueryCondition`. Predicate construction
  and `and/or/not` composition continue through the same column methods.
- Remove type arguments from declarations of `SortSpecification<Entity>`.
- Replace explicit `ProjectionSelectFromStep<Result>` declarations with `SelectFromStep<Result>`.
  Typical chained `operations.select(projection).from(table)` calls require no source change.

These breaks deliberately remove root-generic constraints that cannot model aggregate,
cross-source, scalar-subquery, or derived-column conditions. Compatibility adapters would retain
the duplicate API hierarchy and make later expression kinds expand several parallel paths, so the
internal 0.2.x development policy approves direct replacement with the shared contracts.

## Step 4: query-block scope analysis

### Added API

- `SemanticValidator#analyzeComplete(SelectStatement)` validates a top-level SELECT and returns an
  immutable `QueryBlockAnalysis` instead of storing context-dependent state on the AST.
- `QueryBlockPath` and `QueryBlockLocation` identify blocks by deterministic embedding structure;
  `ResolvedSourceIdentity`, `ResolvedColumnIdentity`, and `ResolvedParameterIdentity` identify
  resolved dependencies without JVM object addresses or runtime values.
- `ResolvedExpression` exposes each clause expression's resolved structure key, ordered column and
  parameter dependencies, exact `ExpressionPosition`, and effective nullability.
- `QueryBlockAnalysis#analyzeChild(...)` is the context-bearing analysis entry used by subsequent
  subquery AST slices. It is diagnostic/compiler infrastructure, not a user-extensible expression
  or source SPI.

These are additive APIs. Existing `SemanticValidator#validate(...)` and `validateComplete(...)`
void entry points remain and now delegate SELECT scope work to the same analyzer.

### Validation behavior

- A top-level SELECT rejects every column that is not owned by its current query block. A nested
  analysis may bind such a column only to an object-identical source in its visible ancestor chain.
- Join ON uses the exact stage scope: accumulated left sources plus the current right source, before
  the current outer Join applies null extension. A CROSS JOIN has no ON location or corresponding
  child-analysis scope. SELECT/WHERE/GROUP BY/HAVING/ORDER BY and pagination use the completed
  source state.
- Reusing the same table-expression object in a current block and visible ancestor is rejected.
  Structurally equal aliases cannot impersonate one another. Equal effective qualifiers in separate
  blocks remain legal unless the nearer source would shadow an actual correlated ancestor target.
- Effective qualifiers longer than 63 UTF-8 bytes fail complete SELECT analysis. This portable
  boundary prevents PostgreSQL identifier truncation from turning two distinct aliases into a
  shadowing collision after validation.
- Ordinary relation-source children are non-correlated. `JOIN_SOURCE` child analysis intentionally
  drops the parent scope; LATERAL/APPLY remains deferred.
- Scope diagnostics now start with a stable query-block path and exact clause/item/operand position.
  Code that asserted the older single-block message text should match the new structured diagnostic.
- Query compilation invokes complete semantic validation before dialect capability validation and
  rendering. Renderers retain their defensive validation for direct AST callers; a third-party
  renderer cannot become the query compiler's only semantic-validation boundary.
- Unknown `SqlExpression` implementations fail closed. Every framework-owned expression kind must
  be added explicitly to local validation, effective-nullability and query-scope resolution, and
  rendering before use; an unknown node is never treated as a dependency-free structural leaf.

Resolved block keys remain query-analysis output in this foundation step. They are not eagerly
recomputed by every executable query solely for continuation fingerprints; the later recursive
compiler integration will carry the already-resolved key forward without duplicating scope
analysis. This does not modify the shared query-plan cache key or cache-consistency semantics
governed by the ADR requirement.

## Step 5: reusable SELECT descriptions and execution adaptation

### Added API

- `Sql.select(...)` and `Sql.selectFrom(...)` create framework-owned, immutable descriptions
  independently from `QueryOperations`.
- `SelectDescription<R>` and `NonNullSelectDescription<R>` represent general result shapes;
  `SingleColumnSelect<V>` and `NonNullSingleColumnSelect<V>` preserve exactly-one-visible-column
  shape across the complete current fluent chain. Single-column shape does not state result
  cardinality.
- `QueryOperations#query(description)` and `query(description, QueryParameters)` adapt descriptions
  into the existing `SelectQuery` or `NullableSelectQuery` execution contracts. The methods are
  defaults that fail explicitly for third-party implementations, preserving binary compatibility;
  built-in executors and Sessions override them.
- `Selectable` adds strongly typed `QueryParameter<V>` overloads for comparisons, BETWEEN, LIKE,
  and explicitly named `inParameters`/`notInParameters` membership.

### Separation and compatibility behavior

A description stores only immutable SQL construction state, opaque parameter references, and the
result-shape contract. It has no terminal operations and no reference to an executor, connection,
Session, transaction, execution options, or ordinary value. Missing and surplus bindings fail
before JDBC. Existing `select(...).from(...)` and `selectFrom(...)` entry points remain available and
use the same internal state, query-block analysis, result mapping, and value-independent plan cache.

Terminal pagination remains an execution adapter concern and is combined with the shared SELECT
state only for the final top-level SQL AST. It is not persisted into a reusable description. A
direct description-level SQL limit/offset API and all embedding adapters remain deferred to their
explicit later capability slices.
