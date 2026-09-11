# 0.2.5 API compatibility ledger

This ledger records the public API differences approved for steps 1 and 3 of the internal
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
