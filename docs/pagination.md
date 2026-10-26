# Page and slice pagination

The current `0.2.9-SNAPSHOT` query API exposes two immutable pagination results: `Page<R>` and
`Slice<R>`. Offset and keyset are request/execution choices; they do not create additional public
result types.

The aggregate/grouped/derived offset and wrapped-count extensions passed Java 21/25,
PostgreSQL/H2 and independent-consumer verification in
[CI](https://github.com/xhufang/skis/actions/runs/40284163961) at PR #91 merge commit `980ad24`. See [aggregates and grouped results](aggregates-and-grouping.md) for types and examples.
No local build or test was run.

## Stable ordering

Paginated queries must declare a stable `ORDER BY`, except when their final result is proven to
contain at most one row. SKIS never appends a primary key silently.
For an ordinary entity or projection query, include the complete primary key explicitly or use
`thenByPrimaryKey(...)`:

```java
SelectQuery<Pet> query =
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

For a non-distinct Join, the current conservative proof requires the complete primary key of the
root and every joined occurrence that can contribute a different result-row combination. Calling
`thenByPrimaryKey(...)` adds only the root key. A distinct Join instead uses the visible selected
tuple as its row identity and does not add hidden joined keys. Effective outer-join nullability is
used for keyset validation, so a physically non-null column on a null-extended side still requires
explicit null placement.

Final identity is considered in this order: an at-most-one-row proof, the final visible DISTINCT
tuple, the row-dependent keys of a non-DISTINCT grouping, then ordinary source identities. Global
aggregates and groups containing only row-independent constant/parameter keys may omit ordering.
Explicit constant grouping still returns zero groups on empty input; a global aggregate can return
one row on empty input. Mixed grouping does not require ordering its row-independent components.

For grouped offset Page/Slice, explicitly order all row-dependent grouping keys, optionally after
aggregate ordering terms. DISTINCT after grouping needs only the complete visible DISTINCT tuple.
Derived sources propagate only complete, unchanged exported identities. Repeated raw exports of
the same component are interchangeable for ordering and further export, including after an outer
DISTINCT. That DISTINCT retains its complete visible tuple while accepting either raw alias as the
ordering representative; other outputs and independent parameters still require coverage. A cast, computation on an
identity component or partial export cannot substitute for that identity. Complete raw export also
preserves a proof that the identity tuple cannot be entirely NULL, even when each column is nullable.
Nullable grouped keys are valid identities, but a FULL JOIN whose two identity tuples can each be
entirely NULL requires an explicitly exported and ordered non-null presence component. A derived
root cannot use `thenByPrimaryKey`.

DISTINCT may order a selected correlated scalar subquery or a selected expression containing one.
Each original occurrence is validated before matching selected outputs, and independent logical
parameters remain distinct. FULL JOIN presence may come from either side or any valid exported
candidate; ordering need not include an arbitrarily preferred left-side output.

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
metadata proves complete rows are unique. Other multi-expression DISTINCT and grouped/aggregate
results use an equivalent AST wrapper: `SELECT COUNT(*) FROM (final content query) AS ...`.
This counts final rows after grouping, HAVING and DISTINCT, including the complete NULL-containing
DISTINCT tuple. Only the outer Long count is decoded; no inner projection object is materialized.

For an empty input, `SELECT COUNT(*)` returns one row whose value is zero, so its Page total is one.
HAVING may remove that row and make the total zero. An out-of-range page has empty items but retains
the full unpaged total. Count removes terminal pagination and presentation-only ordering, preserving
nested semantic ordering/limits and aggregation introduced only by ordering. Content and count have
separate final parameter layouts; count has no terminal pagination parameters or JDBC maxRows limit.

If an automatic count cannot be compiled equivalently, callers can supply an equivalent `CountQuery`
built from another query:

```java
CountQuery explicitCountQuery =
    executor.selectFrom(pet)
        .where(pet.tenantId().eq(tenantId))
        .countQuery();

Page<PetSummary> page = summaryQuery.fetchPage(request, explicitCountQuery);
```

The count source carries its own complete query shape and parameter values. It must be a built-in
SKIS `CountQuery` created with `countQuery()` in the same executor/session and use the same execution
options as the content query. Callers are responsible for equivalence. An arbitrary Long selection
query cannot be supplied as the total; `countQuery()` always counts final rows rather than reading a
Long aggregate selection value.

Join count retains the exact FROM/JOIN/ON/WHERE structure. Ordinary count therefore preserves
Join-expanded duplicate rows. A distinct complete entity with a single-column primary key counts
that selected occurrence's key; a nullable outer-joined entity also counts one absent entity result
when unmatched rows exist. Multi-expression DISTINCT and composite-key shapes outside these proven
direct paths use the equivalent AST wrapper.

## Offset slice

An offset slice executes only content SQL and reads one internal extra row:

```java
Slice<Pet> first = query.fetchSlice(SliceRequest.offset(0, 20));
```

The SQL limit is `pageSize + 1`. The extra row is excluded from `items()` and only determines
`hasNext()`. When another slice exists, `nextContinuation()` contains the next offset. No count
statement is executed.

Offset slices accept legal computed, aggregate and derived-column ordering when the complete final
identity is covered. Their continuation records resolved expression structure, direction and NULL
placement, without selecting or decoding ordering values. For example:

```java
var grouped = executor.select(Sql.countAll()).from(pet)
    .groupBy(pet.name()).orderBy(pet.name().asc());
Page<Long> page = grouped.fetchPage(PageRequest.page(0, 20));
Slice<Long> slice = grouped.fetchSlice(SliceRequest.offset(0, 20));
```

The Page total counts names/groups, while each item is the number of pets in that group.

## Forward keyset slice

Keyset ordering requires physical columns. Computed, aggregate and derived-column ordering is
rejected before JDBC, and grouped/derived keyset is unsupported, including its first request.
Global aggregates use offset Slice even though their result contains at most one row.

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

Mutable keyset representations are defensively copied both when the continuation is created and
when its internal values are read for resume. This includes arrays and the built-in
`java.sql.Date`/`Time`/`Timestamp` representations.

For Join queries, the structural identity also includes Join kind and order, every table occurrence
and alias, ON structure, selected shape, and distinct state. Two occurrences of the same physical
table cannot exchange continuations.

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
