# Explicit joins and generated result rows

This guide describes the explicit Join contract implemented by the internal `0.2.4-SNAPSHOT`
milestone. Joins use generated `QueryTable` instances only. SKIS does not infer associations,
navigate entity properties, or issue hidden SQL.

## Join forms and ON staging

`join(...)` is an alias for `innerJoin(...)`. INNER, LEFT, RIGHT, and FULL joins return a
`JoinOnStep`; no terminal operation is available until `.on(...)` completes the join. CROSS JOIN
returns the next immutable query directly and never accepts an ON condition.

```java
SelectQuery<Pet, Pet> query =
    executor
        .selectFrom(pet)
        .leftJoin(owner)
        .on(pet.ownerId().eq(owner.id()))
        .where(pet.name().eq("Mimi"));
```

The current dialect matrix is intentional:

| Join | PostgreSQL | H2 |
| --- | --- | --- |
| INNER | supported | supported |
| LEFT | supported | supported |
| RIGHT | supported | supported |
| FULL | supported | rejected before JDBC |
| CROSS | supported | supported |

An unsupported form fails during dialect validation and identifies the dialect, Join kind, and
one-based Join position. SKIS does not emit guessed fallback SQL.

## Scope, order, and aliases

Each query block has one root occurrence followed by Join occurrences in call order. An ON
condition may reference the accumulated left side and its current right table. It may not reference
a table that will be joined later or a different table object that happens to have the same
metadata and alias. WHERE, SELECT, projection selections, and ORDER BY are validated against the
completed Join scope.

Use a generated table's `as(...)` method whenever the same entity occurs more than once:

```java
OwnerTable primaryOwner = OwnerTable.OWNER.as("primary_owner");
OwnerTable reviewer = OwnerTable.OWNER.as("reviewer");

executor
    .select(PetOwnerPairViewProjection.of(
        pet.id(), primaryOwner.name(), reviewer.name()))
    .from(pet)
    .leftJoin(primaryOwner)
    .on(pet.ownerId().eq(primaryOwner.id()))
    .leftJoin(reviewer)
    .on(primaryOwner.id().eq(reviewer.id()))
    .fetchList();
```

Aliases are validated identifiers and participate in the query structure. Two aliases of the same
physical table remain distinct occurrences in SQL, result mapping, ordering, and continuation
fingerprints.

## Outer-join nullability

Physical column nullability and effective query nullability are different. A non-null owner column
on the right side of a LEFT JOIN is effectively nullable after that Join. Selecting it through a
required result API fails before JDBC:

```java
NullableSelectQuery<Pet, String> ownerNames =
    executor
        .selectNullable(owner.name())
        .from(pet)
        .leftJoin(owner)
        .on(pet.ownerId().eq(owner.id()));
```

Use `selectNullable(table)` for an entity that can be absent. An absent row is detected from its
complete non-null primary key before the generated entity decoder runs. All key parts NULL means
the entity is absent, all non-NULL means it is present, and a partially NULL composite key is a
mapping/database contract failure. A nullable entity without complete primary-key metadata is
rejected during query compilation.

Effective nullability is applied only after the current ON condition has been validated. For
example, the right table's declared non-null ID is still non-null while validating that Join's ON,
but becomes nullable in the final SELECT after a LEFT JOIN.

## Generated cross-table projections

`@SkisProjection` describes constructor parameters, not a source entity. The generated companion
binds each parameter position to an explicit selectable:

```java
@SkisProjection
public record PetOwnerView(long petId, String petName, @Nullable String ownerName) {}

List<PetOwnerView> rows =
    executor
        .select(PetOwnerViewProjection.of(pet.id(), pet.name(), owner.name()))
        .from(pet)
        .leftJoin(owner)
        .on(pet.ownerId().eq(owner.id()))
        .fetchList();
```

The fixed generated method lets javac check arity, ordered Java types, and declared physical
nullness. Query compilation then resolves table occurrences and codecs and checks SQL type and
effective outer-join nullability. The decoder reads fixed one-based ResultSet indexes and calls the
constructor directly; there is no result-Class lookup, projection registry, reflection, or column
name matching.

## Duplicate rows, ON, and WHERE

Join row multiplicity is ordinary SQL behavior. Selecting one owner joined to two pets returns two
owner values. SKIS never deduplicates by entity ID. Call `distinct()` only when the final selected
value tuple should be distinct.

Predicates retain their SQL location. A filter in a LEFT JOIN's ON clause restricts matches while
preserving unmatched left rows. Moving the same filter to WHERE can remove those rows. SKIS does
not relocate predicates between ON and WHERE.

## Ordering, pagination, and count

ORDER BY may use any column in the completed Join scope. Paginated non-distinct joins require a
conservative stable order that covers the complete primary key of every occurrence that can
participate in a result-row combination. `thenByPrimaryKey(...)` adds only the root table key; it
does not fill keys for joined occurrences.

For distinct results, stability is based on the visible selected value tuple. Every selected
expression must be covered by ORDER BY, and hidden joined keys are not added because that would
change distinct semantics. Keyset ordering uses effective Join nullability, so every null-extended
ordering column must explicitly choose `nullsFirst()` or `nullsLast()`.

Automatic count keeps the same FROM/JOIN/ON/WHERE structure after removing ordering and pagination:

- non-distinct uses `COUNT(*)`, preserving Join-expanded rows;
- a distinct single expression uses `COUNT(DISTINCT expression)` and also counts one NULL result
  when needed;
- a complete distinct entity with one primary-key column counts that occurrence's key, including
  one absent nullable-entity result when present;
- a multi-expression distinct shape or composite-key shape without a portable equivalent requires
  an explicit `CountQuery`; Slice remains available without count.

Continuations include Join kind and order, occurrence identity, alias, ON structure, result shape,
distinct state, and ordering signature. Ordinary parameter values remain outside the structural
fingerprint and are validated through a separate digest.

## Execution boundaries

Single-table unfiltered and single-property equality entity queries retain their bounded Fast Path
plans. `findById` and single-entity mutation continue to use their precompiled paths and do not build
Join structures. Join and generated projection plans stay immutable and query-local; no unbounded
cache, runtime annotation scan, SQL parser, or per-row reflection is introduced.
