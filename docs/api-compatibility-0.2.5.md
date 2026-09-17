# 0.2.5 API compatibility ledger

This ledger records the public API differences approved for steps 1, 3, 4, 5, 6, 7, 8, and 9 of the internal
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

## Step 6: EXISTS and correlated subqueries

### Added API

- `ExistsPredicate` represents native EXISTS/NOT EXISTS over a complete `SelectStatement` subtree.
  Its result is a non-null Boolean and its equality/hash structure includes the complete child and
  negation flag.
- `Sql.exists(SelectDescription<?>)` and `Sql.notExists(SelectDescription<?>)` embed any reusable
  result shape as a `QueryCondition`; descriptions still expose no execution operations.
- `DialectFeature.EXISTS_SUBQUERY` and `DialectFeature.CORRELATED_SUBQUERY` separately declare
  existence syntax and ancestor-reference support. PostgreSQL and H2 enable both.
- `QueryBlockAnalysis#nestedBlocks()` exposes deterministic child occurrences and
  `QueryBlockAnalysis#correlated()` reports ancestor dependency. Independent count analysis now has
  the matching `SemanticValidator#analyzeComplete(CountAst)` entry.

### Validation and execution behavior

EXISTS children are analyzed at their exact SELECT, WHERE, Join ON, GROUP BY, HAVING, ORDER BY, or
pagination location. Join ON therefore cannot see a future source. A standalone correlated
description still fails before JDBC, while embedding the same immutable description supplies the
validated ancestor scope without writing bindings back to its AST.

The final outer statement assigns dense logical slots across all child occurrences. Reusing one
parameterized child twice allocates two slots backed by the same query-level parameter binding, and
the renderer records both JDBC positions in SQL encounter order. Nested blocks share only the outer
statement's SQL buffer and binding plan: they create no child PreparedStatement and run no child
decoder.

Direct renderer calls perform the same recursive semantic and dialect capability checks. A dialect
may support independent EXISTS while rejecting correlation. PostgreSQL/H2 SQL preserves the child
selection list and query clauses and performs no selection pruning, limit injection, or Join
rewrite.

## Step 7: IN/NOT IN subqueries

### Added API

- `InSubqueryPredicate<T>` represents native IN/NOT IN membership over a complete one-column
  `SelectStatement`. It is distinct from collection-backed `InPredicate<T>`.
- `Selectable<V>#in(SingleColumnSelect<V>)` and `notIn(...)` add invariant, strongly typed child
  description overloads. Existing collection and parameter-collection overloads are unchanged.
- `DialectFeature.IN_SUBQUERY` independently declares subquery membership syntax. PostgreSQL and H2
  enable it; correlated membership additionally requires `CORRELATED_SUBQUERY`.
- Nested block analysis now exposes `NestedQueryKind`, allowing recursive capability validation and
  structure fingerprints to distinguish EXISTS from IN occurrences.

### Validation and semantic behavior

Both the query DSL and low-level AST validate exact boxed Java types plus the shared SQL equality
compatibility rule. The AST counts visible and hidden outputs and rejects every child whose final
physical shape is not exactly one column. Result Java classes and projection constructor arity are
never used as substitutes for SQL shape.

Complete analysis resolves effective nullability on both sides in their own query-block scopes.
Either nullable side makes the Boolean result conservatively nullable. SQL evaluates the child in
the outer statement: SKIS does not materialize values, remove NULLs, deduplicate, add CASTs, or
rewrite IN/NOT IN to Join/EXISTS. This preserves the empty-child, NULL, duplicate, and NOT IN versus
NOT EXISTS contracts.

## Step 8: scalar subqueries

### Added API

- `ScalarSubqueryExpression<T>` represents a complete SELECT subtree as one SQL value and validates
  that its final visible plus hidden physical output count is exactly one.
- `Sql.scalar(SingleColumnSelect<V>)` returns a `Selectable<V>`; its public type is deliberately
  nullable and cannot be assigned to `NonNullSelectable<V>`.
- `DialectFeature.SCALAR_SUBQUERY` independently declares scalar-subquery syntax. PostgreSQL and H2
  enable it; correlated scalar expressions additionally require `CORRELATED_SUBQUERY`.
- `QueryBlockAnalysis.NestedQueryKind.SCALAR_SUBQUERY` distinguishes scalar child occurrences in
  recursive analysis and structure keys.

### Validation and execution behavior

The child selection supplies the boxed Java type, portable SQL type, and result Codec. The scalar
boundary always has nullable effective nullability because a zero-row child evaluates to SQL NULL;
there is no non-null assertion overload. A generated projection must therefore declare the target
parameter nullable. The outer result mapping reads the scalar's physical result column directly and
does not run the child's result decoder.

Zero rows and one NULL row both evaluate to NULL, one non-null row evaluates to its value, and more
than one row remains a database cardinality error. No limit, preflight query, Java materialization,
or first-row truncation is introduced. Driver failures continue through the existing query exception
translation with SQLState, vendor code, cause, and cleanup failures preserved.

DISTINCT ordering by a selected scalar uses the selected output's one-based SQL position, retaining
its parameter slots instead of generating a second parameterized subquery. The original ordering
occurrence is validated before reuse. This fixes PostgreSQL DISTINCT matching without changing the
public DSL, AST constructors, or generated ABI; independent parameter references remain distinct.

## Step 9: derived relations

### Added API

- `DerivedOutput<V>` and `NonNullDerivedOutput<V>` are explicit typed handles for one named output
  in an ordered derived shape. `Sql.output(...)` preserves declared nullability and
  `Sql.outputNullable(...)` explicitly weakens a declared non-null selection.
- `DerivedRelation` is a framework-owned reusable relation created by
  `Sql.derived(description, alias, outputs...)`. `column(handle)` returns a `Selectable<V>` or
  `NonNullSelectable<V>` without accepting a string column name.
- Static descriptions and executable query stages accept a `DerivedRelation` as a root or Join
  right source. Entity roots retain `SelectQuery<Entity, R>`; a derived root exposes the neutral
  `SelectQuery<?, R>` or `NullableSelectQuery<?, R>` view.
- The AST adds `DerivedRelationSource`, `DerivedRelationReference`, `DerivedOutputColumn`, and
  `DerivedColumnExpression<T>`. Resolved dependencies use the common `ResolvedColumnReference`,
  with physical and derived identities distinguished explicitly.
- `DialectFeature.DERIVED_TABLE` declares FROM/Join SELECT support. PostgreSQL and H2 enable it.

### Join ON-step generic simplification

The right-source entity parameter `J` has been removed from `JoinOnStep`, `NullableJoinOnStep`,
`SelectDescriptionJoinOnStep`, and their nullability/single-column specializations. Entity-table
and derived-relation overloads now return the same ON-step type; entity-table parameters use
`QueryTable<?>`, and `crossJoin(QueryTable<?>)` no longer declares a method type parameter that is
absent from its result.

This does not remove an enforced query constraint: `on(...)` has always accepted the
non-parameterized `QueryCondition`, while right-source visibility is resolved from the concrete
relation occurrence during complete semantic validation. The package-owned `QueryRelation`
continues to unify implementations without becoming a public parameter type.

Source that explicitly names the old phantom argument must drop it, for example
`JoinOnStep<Pet, Pet, Owner>` becomes `JoinOnStep<Pet, Pet>`. Fluent calls such as
`query.leftJoin(owner).on(condition)` retain their form.

### Validation and compatibility behavior

Output aliases are mandatory and unique, and handles must match the visible selection count,
order, Java types, SQL types, and logical expression occurrences. A handle from another shape is
rejected by identity even when its descriptor is equal. `DerivedRelation#as(...)` creates another
concrete source occurrence over the same immutable description and output handles.

Complete inner analysis freezes effective output nullability after all inner Joins. Outer Joins
apply null extension to the derived occurrence again. The query mapper recursively obtains each
derived result and parameter Codec from its original selectable; no entity metadata, primary key,
`EntityRuntimeModel`, temporary DTO, or inner row decoder is synthesized.

Derived sources are non-correlated. An outer/sibling capture and an outer reference to an inner
unpublished physical column fail before JDBC. Renderer and compiler traversal include derived
children in recursive semantic and dialect checks, preserve dense nested parameter layout, and
render explicit output aliases. Existing entity-only constructors and DSL overloads remain.
