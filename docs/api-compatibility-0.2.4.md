# 0.2.4 projection API compatibility ledger

This ledger records only the projection differences approved for T14 in the internal
`0.2.4-SNAPSHOT` milestone. It is not the consolidated compatibility report for the unfinished
`0.2.x` development line. The root `pom.xml` japicmp allow-list names the approved removals
precisely so unrelated public API breaks continue to fail compatibility checks.

## Added API

- Sealed, framework-owned `Selectable<V>` and `NonNullSelectable<V>` selection contracts.
- Immutable `ProjectionMapping<R>` constructor mappings and `ProjectionSelection<R>` query bindings.
- `ProjectionSelectFromStep<R>` with an independent `<F> from(QueryTable<F>)` root type.
- `QueryOperations#select(ProjectionSelection<R>)`.
- Generated, application-visible `ResultTypeProjection.of(...)` methods with fixed arity and typed
  selectable parameters.

`ResolvedResultShape<R>` is deliberately package-private and is not a public extension point.

## Approved removals

- `SkisProjection#entity()` and the `ProjectionProperty` annotation.
- Entity-bound `Projection<E,R>`, `ProjectionProvider`, and `ProjectionRegistry`.
- `QueryOperations#selectProjection(QueryTable<E>, Class<R>)`.
- `QueryRuntime#create/compile` overloads that accepted `ProjectionRegistry`.
- `SkisExecutorFactory.Builder#projectionRegistry(ProjectionRegistry)`.
- Projection startup discovery through `ProjectionModelLoader`,
  `SkisProjectionIndexProcessor`, and `META-INF/skis/projections.idx`.

The loader and index processor were infrastructure rather than application APIs, but they are
listed because their removal changes generated-artifact and assembly behavior.

## Generated ABI

The generated-model ABI is 5. A projection companion embeds the ABI plus a stable mapping ID made
from the result binary name, selected constructor JVM descriptor, ordered parameter names, erased
boxed Java types, nullness contracts, and ABI version. The runtime checks the embedded ABI when the
mapping initializes. Entity index headers use the same ABI, so stale generated entities fail during
runtime assembly with an instruction to regenerate matching entity and projection sources.

The new runtime performs no projection lookup by result `Class`, no unchecked registry narrowing,
and no compatibility placeholder for the deleted query entry point. The compatibility record will
be consolidated only after the remaining `0.2.x` milestones and breaking changes are complete.
