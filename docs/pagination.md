# Page and slice pagination

The `0.2.3-SNAPSHOT` query API exposes two immutable pagination results: `Page<R>` and
`Slice<R>`. Offset and keyset are request/execution choices; they do not create additional public
result types.

## Stable ordering

Every paginated query must declare a stable `ORDER BY`. SKIS never appends a primary key silently.
For an ordinary entity or projection query, include the complete primary key explicitly or use
`thenByPrimaryKey(...)`:

```java
SelectQuery<Pet, Pet> query =
    executor.selectFrom(pet)
        .where(pet.active().eq(true))
        .orderBy(pet.createdAt().desc().nullsLast())
        .thenByPrimaryKey(SortDirection.DESC);
```

A distinct result is also stable when its ordering covers every selected distinct expression.
`distinct()` additionally requires every ordering expression to be present in the visible
selection. A nullable keyset ordering item must use `nullsFirst()` or `nullsLast()`; relying on a
database default is rejected because it cannot define a portable continuation.

PostgreSQL and H2 use native `NULLS FIRST`/`NULLS LAST`. The keyset predicate applies the same
null-ranking rules as the rendered order.

## Page

`PageRequest.page(pageIndex, pageSize)` uses a zero-based page index:

```java
Page<Pet> page = query.fetchPage(PageRequest.page(2, 20));
```

One terminal operation executes an offset content plan and an independent count plan on the same
acquired connection and execution context. SKIS does not start a transaction; callers that need a
snapshot across the two statements must supply the required transaction isolation. No `Page` is
returned unless both statements succeed.

Automatic count uses `COUNT(*)` for ordinary single-table results and `COUNT(DISTINCT expression)`
for a non-null single-expression distinct result. For a nullable expression, the count also adds
one when the filtered rows contain `NULL`. This matches the single `NULL` row returned by `SELECT
DISTINCT`. A multi-expression distinct entity result may use `COUNT(*)` only when primary-key
metadata proves complete rows are unique; a multi-expression result that cannot be counted
equivalently is rejected. Such a query can provide an explicit `CountQuery` built from another
query whose count is known to be equivalent:

```java
CountQuery explicitCountQuery =
    executor.selectFrom(pet)
        .where(pet.tenantId().eq(tenantId))
        .countQuery();

Page<PetSummary> page = summaryQuery.fetchPage(request, explicitCountQuery);
```

The count source carries its own table, predicate, distinct shape and parameter values; ordering is
discarded. It must be a built-in SKIS query in the same executor/session and use the same execution
options as the content query.

## Offset slice

An offset slice executes only content SQL and reads one internal extra row:

```java
Slice<Pet> first = query.fetchSlice(SliceRequest.offset(0, 20));
```

The SQL limit is `pageSize + 1`. The extra row is excluded from `items()` and only determines
`hasNext()`. When another slice exists, `nextContinuation()` contains the next offset. No count
statement is executed.

## Forward keyset slice

Start and resume keyset traversal with the same query structure, predicate values, ordering and
page size contract:

```java
Slice<Pet> first = query.fetchSlice(SliceRequest.keysetFirst(20));

Slice<Pet> second =
    query.fetchSlice(
        SliceRequest.resume(first.nextContinuation().orElseThrow(), 20));
```

`SliceContinuation` is opaque and immutable. Internally it binds the position to the query
selection and predicate structure, ordering direction/null placement, SQL and Java types,
predicate-parameter digest, format version and generated-model ABI. It never exposes or prints raw
ordering values. `0.2.3` supports forward continuation only.

For projections, keyset execution may select hidden ordering columns such as
`__skis_order_0`. User decoders still receive only their declared visible fields. A distinct query
is rejected if adding those hidden columns would change distinct semantics.

## Limits and validation

- `pageSize` must be positive and `pageIndex`/offset must not be negative.
- Offset multiplication and `pageSize + 1` are checked for overflow.
- A visible page size above the effective `ExecutionOptions.maxRows` fails before execution.
- The internal extra slice row does not count against the user-visible `maxRows` limit.
- A continuation from a different selection, predicate value set, ordering, type or ABI fails
  before JDBC execution.
