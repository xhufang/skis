# EXISTS, IN, scalar subqueries, derived tables, and correlated SELECT descriptions

This document describes the EXISTS, IN, scalar-subquery, and derived-table slices implemented by the internal
`0.2.5-SNAPSHOT` milestone.
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

## One-column IN subqueries

Every `Selectable<V>` also accepts a `SingleColumnSelect<V>` through `in(...)` and `notIn(...)`.
The overload is separate from collection membership: the child remains a SELECT subtree in the
one final SQL statement and is never read into a Java collection.

```java
PetTable candidate = PetTable.PET.as("candidate_pet");
var ownerIds =
    Sql.select(candidate.ownerId())
        .from(candidate);

List<Owner> owners =
    executor
        .query(Sql.selectFrom(owner).where(owner.id().in(ownerIds)))
        .fetchList();
```

`SingleColumnSelect<V>` proves one SQL value per row, not one result row. Its invariant `V` must
exactly match the boxed Java type on the left, and both SQL types must pass the shared equality
compatibility rule. SKIS does not widen numbers or insert a CAST. A generated projection remains a
general result shape even when its constructor has one argument. A low-level child with a hidden
ordering selection has more than one physical output and is rejected before rendering.

Native SQL three-valued logic is preserved:

| Child result and left value | `IN` | `NOT IN` |
| --- | --- | --- |
| An equal non-null value exists | true | false |
| No equal value and the child has no NULL | false | true |
| No equal value and the child contains NULL | unknown | unknown |
| Left is NULL and the child is non-empty | unknown | unknown |
| Child is empty, including when left is NULL | false | true |

Duplicate child values do not duplicate outer rows. SKIS does not filter child NULL values or
rewrite IN to a Join. In particular, nullable `NOT IN` is intentionally not rewritten to `NOT
EXISTS`; those forms are not equivalent when the child can produce NULL.

## One-column scalar subqueries

`Sql.scalar(SingleColumnSelect<V>)` turns a reusable one-column description into a nullable
`Selectable<V>` that can be selected, compared, checked for NULL, sorted, or bound to a nullable
generated-projection parameter. It remains part of the outer SQL statement and is not executed by
itself.

```java
OwnerTable lookup = OwnerTable.OWNER.as("owner_lookup");
var ownerName =
    Sql.scalar(
        Sql.select(lookup.name())
            .from(lookup)
            .where(lookup.id().eq(pet.ownerId())));

List<String> names =
    executor
        .select(ownerName)
        .from(pet)
        .where(ownerName.isNotNull())
        .orderBy(ownerName.asc())
        .fetchList();
```

The result type and JDBC Codec come from the child selection. The scalar boundary is nevertheless
always nullable: zero child rows and one child row containing SQL NULL both evaluate to NULL, while
one non-null row evaluates to that value. More than one child row is a database cardinality error.
SKIS does not add `LIMIT 1`, issue a preliminary query, truncate duplicate rows, or translate that
database error into the top-level `NonUniqueResultException`.

When the outer query has a row but the scalar evaluates to NULL, nullable `fetchOne()` returns
`SingleRow.Present(null)`. `SingleRow.NoRow` means that the outer query itself returned no row; SQL
does not expose whether a scalar NULL came from zero child rows or one child NULL. A scalar cannot
be assigned to `NonNullSelectable<V>` or passed to a generated projection parameter whose contract
is non-null. There is no unchecked non-null scalar factory.

Independent query parameters remain distinct when comparing scalar ordering expressions, even if
their child SQL structures otherwise match. Empty collection `in(...)`/`notIn(...)` still evaluates
to false/true: SKIS validates the complete operand and its declared bindings, then excludes its
unused parameters from the final SQL and binder. Automatic count likewise validates the original
selection before removing selection-only parameters.

When a DISTINCT query orders by a selected scalar, SKIS renders its one-based output position
(for example, `ORDER BY 1 ASC NULLS LAST`). The ordering reuses the selected expression's final
parameter slots instead of emitting another copy of the subquery. This matters for PostgreSQL:
two JDBC `?` positions become different server parameters even when they bind the same value,
so repeating the parameterized subquery in ORDER BY would fail DISTINCT expression matching.
The complete original ordering occurrence is still validated before this reuse, and independent
query parameters cannot be treated as the same selected expression.

For the employee/department model in `skis-test-model`:

```java
var employee = EmployeeTable.EMPLOYEE.as("e");
var department = DepartmentTable.DEPARTMENT.as("d");
var pattern = Sql.parameter(String.class, "departmentPattern");
var departmentId = Sql.scalar(
    Sql.select(department.id()).from(department)
        .where(department.id().eq(employee.departmentId())
            .and(department.name().like(pattern))));
var description = Sql.select(departmentId).from(employee)
    .distinct().orderBy(departmentId.asc().nullsLast());
var departmentIds = executor.query(description, QueryParameters.of(pattern, "%部")).fetchList();
```

The SQL contains the department lookup once and ends with `ORDER BY 1 ASC NULLS LAST`. Employees
whose departments do not match the pattern contribute a NULL value; DISTINCT retains one such
NULL. Dialects without native NULLS FIRST/LAST reject explicit null placement for this ordering
before JDBC rather than emitting an invalid CASE over an output ordinal.

Scalar ordering is available for ordinary queries and offset `Page` queries with a provably stable
order. `Slice` currently requires physical-column ordering because its continuation signature does
not yet support scalar expressions. Both offset and keyset slices reject scalar ordering before
acquiring JDBC resources, regardless of page size or whether the data would fill another page.

## Derived tables and explicit output shapes

`Sql.derived(description, relationAlias, outputs...)` freezes a reusable description as a FROM or
Join relation. Every visible selection needs an explicitly aliased output handle in the same order:

```java
OwnerTable inner = OwnerTable.OWNER.as("inner_owner");
var ownerId = Sql.output(inner.id(), "owner_id");
var ownerName = Sql.output(inner.name(), "owner_name");
DerivedRelation owners =
    Sql.derived(Sql.selectFrom(inner), "visible_owner", ownerId, ownerName);

var visibleId = owners.column(ownerId);
var visibleName = owners.column(ownerName);

List<String> names =
    executor
        .selectNullable(visibleName)
        .from(pet)
        .leftJoin(owners)
        .on(pet.ownerId().eq(visibleId))
        .fetchList();
```

`DerivedOutput<V>` preserves a nullable selection contract and `NonNullDerivedOutput<V>` preserves
a declared non-null contract. The relation validates unique aliases, exact output count and order,
Java/SQL types, and handle membership. It then analyzes the complete inner Join chain. If an inner
outer Join makes a declared non-null selection effectively nullable, expose it deliberately through
`Sql.outputNullable(...)`. An outer Join can null-extend a published non-null output again, so the
outer result must use `selectNullable(...)` or a nullable projection parameter.

Columns are resolved by concrete derived-source identity and output ordinal. There is no string
lookup and no synthetic entity, property, primary key, runtime model, or child decoder. Result and
parameter Codecs recursively come from the selectable bound to the output handle. A derived root
therefore returns a root-neutral `SelectQuery<?, R>`/`NullableSelectQuery<?, R>` view; entity roots
retain their existing concrete root generic. `owners.as("other_owner")` creates an independent
occurrence over the same inner description and output handles.

A normal derived source is a non-correlated boundary. Its child cannot capture an outer or sibling
source, and the outer query cannot pierce the boundary to use an unselected physical column.
LATERAL/APPLY remains a separate future capability.

## Correlation and scope

A child description refers to an outer source by using the exact outer `QueryTable` object. No
`correlate` registration or string alias lookup is used. Complete semantic analysis resolves the
reference at its embedding location:

- a subquery in WHERE sees the completed outer FROM/Join scope;
- a subquery in Join ON sees only the accumulated left side and that Join's current right source;
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

PostgreSQL and H2 declare `EXISTS_SUBQUERY`, `IN_SUBQUERY`, `SCALAR_SUBQUERY`,
`CORRELATED_SUBQUERY`, and `DERIVED_TABLE`. A dialect
missing one syntax capability rejects only that nested construct; a dialect missing correlation
support accepts an independent child but rejects a child that depends on an ancestor. Validation
is recursive and reports the stable nested query-block path.

Aggregate expressions remain assigned to their subsequent `0.2.5` slice.
