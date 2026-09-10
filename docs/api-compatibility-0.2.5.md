# 0.2.5 relation-source AST compatibility ledger

This ledger records the AST differences approved for step 1 of the internal
`0.2.5-SNAPSHOT` milestone. It is not the consolidated compatibility report for the unfinished
`0.2.x` development line.

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
