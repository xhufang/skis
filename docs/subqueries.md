# EXISTS and correlated SELECT descriptions

This document describes the EXISTS slice implemented by the internal `0.2.5-SNAPSHOT` milestone.
It accumulates toward the public `0.3.0` SQL DSL and is not part of the published `0.2.0` API.

## Execution-free embedding

`Sql.exists(description)` and `Sql.notExists(description)` accept any reusable
`SelectDescription<?>`, including entity, scalar, multi-column generated projection, nullable, and
single-column shapes. EXISTS observes only whether the description returns a row. SKIS preserves
the complete selected values and query tree: it does not prune selections, add `LIMIT 1`, load the
subquery into Java, or rewrite the predicate as a Join.

```java
OwnerTable owner = OwnerTable.OWNER;
PetTable pet = PetTable.PET.as("matching_pet");

var petsOfOwner =
    Sql.select(pet.id())
        .from(pet)
        .where(pet.ownerId().eq(owner.id()));

List<Owner> ownersWithPets =
    executor
        .selectFrom(owner)
        .where(Sql.exists(petsOfOwner))
        .fetchList();

List<Owner> ownersWithoutPets =
    executor
        .selectFrom(owner)
        .where(Sql.notExists(petsOfOwner))
        .fetchList();
```

The predicate is a non-null Boolean condition and composes with `QueryCondition.and`, `or`, and
`not`. `NOT EXISTS` is represented directly; it is not simulated with a nullable comparison.

## Correlation and scope

A child description refers to an outer source by using the exact outer `QueryTable` object. No
`correlate` registration or string alias lookup is used. Complete semantic analysis resolves the
reference at its embedding location:

- an EXISTS in WHERE sees the completed outer FROM/Join scope;
- an EXISTS in Join ON sees only the accumulated left side and that Join's current right source;
- a future Join, sibling block, unrelated alias object, or source outside the ancestor chain is
  rejected before JDBC;
- reusing the same table-expression object as both child source and visible ancestor is rejected as
  ambiguous; create an independent alias for the child source;
- an independently executed description that still contains an outer reference fails before JDBC.

Each nested block receives a stable structural path such as `$/WHERE[0]#0`. Embedding one
description twice creates two independently analyzed occurrences even though the immutable child
description is shared. Context-specific correlation and effective nullability are never written
back to that description.

## Parameters and JDBC ownership

Reusable descriptions continue to use `QueryParameter<V>` plus a separate `QueryParameters`
environment:

```java
QueryParameter<String> name = Sql.parameter(String.class, "name");
var namedPets =
    Sql.select(pet.id())
        .from(pet)
        .where(pet.ownerId().eq(owner.id()).and(pet.name().eq(name)));
var owners = Sql.selectFrom(owner).where(Sql.exists(namedPets));

List<Owner> result =
    executor.query(owners, QueryParameters.of(name, "Mimi")).fetchList();
```

The outer compiler traverses nested blocks in final SQL clause order and assigns one dense
statement-level logical parameter layout. If the same parameterized description is embedded twice,
its occurrences receive separate logical slots that read the same captured binding. The renderer
records each physical `?` in SQL order, and execution creates only the outer `PreparedStatement`.
There is no child decoder, child query, connection, transaction, or resource owner.

## SQL semantics and dialects

An empty child result makes EXISTS false. One row whose selected value is SQL `NULL` makes it true,
as do duplicate rows. NOT EXISTS negates only row existence. Aggregate interoperability remains an
explicit cross-slice acceptance item: step 10 must verify that a global aggregate without HAVING
produces one row even when its input is empty, and step 12 must verify that HAVING can remove that
row. The base EXISTS implementation deliberately performs no transformation that could change
either behavior; complete T06 closure waits for those contracts.

PostgreSQL and H2 declare both `EXISTS_SUBQUERY` and `CORRELATED_SUBQUERY`. A dialect missing the
existence capability rejects every EXISTS occurrence; a dialect missing correlation support accepts
an independent child but rejects a child that depends on an ancestor. Validation is recursive and
reports the stable nested query-block path.

IN/NOT IN subqueries, scalar subqueries, derived sources, and aggregate expressions remain assigned
to their subsequent `0.2.5` slices.
