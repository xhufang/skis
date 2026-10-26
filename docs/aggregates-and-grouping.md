# Aggregates and grouped results

These APIs are implemented in `0.2.9-SNAPSHOT` and accumulate toward public `0.3.0`.
They are outside the published `0.2.0` API. PostgreSQL 16.15 with JDBC 42.7.11 and H2 2.4.240
have real-driver contract evidence; H2 remains a development/test database.

## Aggregate result types

Use the existing `Sql` factories. Input types must match their exact Java/SQL descriptions;
the numeric wildcard in a method signature does not admit every `Number` subtype.

| Factory                  | Supported Java input                                                    | Java result              | Empty or all-NULL input |
|--------------------------|-------------------------------------------------------------------------|--------------------------|-------------------------|
| `countAll()`             | No expression; counts input rows, including Join duplicates             | Non-null `Long`          | `0`                     |
| `count(expr)`            | Framework-recognized scalar expression, including custom-mapped columns | Non-null `Long`          | `0`                     |
| `countDistinct(expr)`    | One built-in equality-supported expression                              | Non-null `Long`          | `0`                     |
| `sumAsLong(expr)`        | `Byte`, `Short`, `Integer`                                              | Nullable `Long`          | SQL NULL                |
| `sumAsBigInteger(expr)`  | `Long`, `BigInteger`                                                    | Nullable `BigInteger`    | SQL NULL                |
| `sumAsBigDecimal(expr)`  | `BigDecimal`                                                            | Nullable `BigDecimal`    | SQL NULL                |
| `sumAsDouble(expr)`      | `Float`, `Double`                                                       | Nullable `Double`        | SQL NULL                |
| `avgAsBigDecimal(expr)`  | `Byte`, `Short`, `Integer`, `Long`, `BigInteger`, `BigDecimal`          | Nullable `BigDecimal`    | SQL NULL                |
| `avgAsDouble(expr)`      | `Float`, `Double`                                                       | Nullable `Double`        | SQL NULL                |
| `min(expr)`, `max(expr)` | Built-in numeric, character and date/time types listed below            | Nullable input Java type | SQL NULL                |

Primitive descriptors use their boxed Java types. `sumAsLong` rejects a `Long` input; use
`sumAsBigInteger` for that input. A result `Class` or target generic type cannot change an
aggregate's fixed return contract. COUNT ignores NULL expression values; COUNT DISTINCT also
removes duplicate non-NULL values. SUM/AVG/MIN/MAX remain conservatively nullable even for a
non-null input column, and do not insert a default zero.

MIN/MAX support the eight numeric types above, `Character`, `String`, `LocalDate`, `java.sql.Date`,
`LocalTime`, `java.sql.Time`, `OffsetTime`, `LocalDateTime`, `java.sql.Timestamp`, `Instant` and
`OffsetDateTime`, each with its exact built-in SQL type and Codec. COUNT DISTINCT supports the
same mappings plus `Boolean`, `UUID` and `byte[]`. MIN/MAX reject those three additional types.
Both reject `OTHER`, custom Codecs and unlisted Java/SQL combinations. Ordinary COUNT does not
impose that mapping whitelist. A custom Codec may participate in grouping when its known SQL type
supports equality; this does not make it a valid MIN/MAX or COUNT DISTINCT input.

BigInteger sums are decoded exactly; fractional values are not truncated. BigDecimal results do
not promise identical database scale or infinite precision. PostgreSQL widens REAL before SUM;
H2 widens small-integer inputs before exact AVG. Integer arithmetic overflow and division by zero
fail; result conversion cannot silently narrow an integer. H2 AVG of DOUBLE NaN/Infinity fails
with SQLState `22018`; floating-point special values are not a portable cross-database promise.
See [JDBC mappings](jdbc-type-mappings.md) for the underlying mapping contract.

## Generated projections and grouping

Declare a user result type; APT generates only its companion mapper:

```java
import io.skis.annotations.ReusableProjection;
import java.math.BigInteger;
import org.jspecify.annotations.Nullable;

@ReusableProjection
public record PetAggregate(@Nullable String name, Long rowCount, @Nullable BigInteger idSum) {}
```

With the generated `PetTable` and `PetAggregatePro` imports, an existing executor, and the
three-field `Pet(id, name, version)` from the independent consumers:

```java
PetTable pet = PetTable.petTable;
var suffix = Sql.parameter(String.class, "suffix");
var key = Sql.concat(pet.name(), Sql.value(suffix));
var count = Sql.countAll();
var template = executor
    .select(PetAggregatePro.of(key, count, Sql.sumAsBigInteger(pet.id())))
    .from(pet)
    .groupBy(key)
    .having(count.ge(1L))
    .orderBy(key.asc());

var query = template.bind(suffix, "-active");
List<PetAggregate> rows = query.fetchList();
```

Grouping accepts physical/derived columns, compatible scalar computations, constants, typed NULLs
and parameters. It does not require a column dependency. Reuse the same `key` or logical parameter
reference wherever SELECT, GROUP BY, HAVING and ORDER BY need the same expression. Independent
`Sql.value(...)` calls remain independent parameters even when their current values are equal.
Where required, dialect lowering introduces a typed single-row parameter relation to preserve
that identity in JDBC SQL; it never inlines the current value.

`groupBy`, `having`, `andHaving` and `orHaving` return immutable queries and also exist on
`SelectDescription` and `SingleColumnSelect`, preserving single-column eligibility. `groupBy`
requires at least one key and can be declared once; duplicate resolved keys fail. `having` can
be declared once; `andHaving`/`orHaving` require an existing HAVING. Their chain is left-associated:
`having(a).orHaving(b).andHaving(c)` means `(a OR b) AND c`. WHERE chaining remains independent.

Every free column in the current block's selected, HAVING or ordered expressions must be covered
by its grouping keys or occur inside a valid aggregate input. Grouping by an entire computation
does not make its individual columns selectable, and SKIS does not infer functional dependencies
or algebraic equivalence. WHERE, ON and GROUP BY cannot contain an aggregate belonging to that
block. Same-block nested aggregates are rejected. Nested SELECT retain independent scopes;
an aggregate written in a child block using only ancestor columns is rejected rather than
silently assigned to the ancestor. These failures occur before JDBC resource acquisition.

## Standard expressions and values

`Sql` also supplies `value`, `add`, `subtract`, `multiply`, `divide`, `caseWhen`, `coalesce`,
`concat` and `cast`. Arithmetic requires matching boxed Java and SQL numeric types; CASE and
COALESCE require matching boxed Java types and compatible SQL types. There is no implicit widening.
Integer division truncates toward zero. BigInteger division is rejected; explicitly cast both
operands to BigDecimal when fractional results are needed. `concat` propagates NULL; use an
explicit `coalesce` fallback to substitute an empty string.

`Sql.value(value)` captures one immutable snapshot and binds it as a parameter; null requires
`Sql.value(Type.class, null)`. The three value overloads accept only the exact built-in classes
listed above, including Boolean/UUID/byte[], and reject arbitrary subclasses and other arrays.
`Sql.cast(expr, Type.class, SqlType)` requires the exact built-in Java/SQL target pair and an
allowed conversion; VARBINARY and OTHER are not portable public CAST targets.

Pure descriptions use `Sql.value(parameter)` and receive values at the executor's binding boundary:

```java
var suffix = Sql.parameter(String.class, "suffix");
var key = Sql.concat(pet.name(), Sql.value(suffix));
var description = Sql.select(PetAggregatePro.of(key, Sql.countAll(), Sql.sumAsBigInteger(pet.id())))
    .from(pet).groupBy(key).orderBy(key.asc());
var rows = executor.query(description).bind(suffix, "-active").fetchList();
```

Passing a value-bearing expression to a pure description fails. Standard expressions contributing
result values or comparison bindings must ultimately use built-in input mappings. A CASE condition
or ordinary COUNT input can use a custom-mapped column without making it a contributing value.
Direct custom-column selection retains its property Codec. Unsupported input mappings fail with
`QUERY_TYPE_MISMATCH`; aggregate results use the fixed built-in result Codec. Effective outer-Join
nullability still applies to generated projection arguments. Decoding uses fixed column indexes.

## Offset pagination and final-row counts

```java
var page = query.fetchPage(PageRequest.page(0, 20));
var explicitPage = query.fetchPage(PageRequest.page(0, 20), query.countQuery());
var slice = query.fetchSlice(SliceRequest.offset(0L, 20));
```

Page totals count unpaged final rows after GROUP BY, HAVING and DISTINCT. They do not read a
selected COUNT value. For example, two returned groups give a total of two regardless of each
group's COUNT. Complex count uses an equivalent AST wrapper; only its outer Long is decoded.
Content and count have independent final parameter layouts. An out-of-range page has empty items
and retains the unpaged total. Slice reads one extra row to determine `hasNext` and executes no count.

Stable ordering must cover the final result identity. For non-DISTINCT groups it covers every
row-dependent grouping key; row-independent constants/parameters need no extra ordering. DISTINCT
uses the complete visible result tuple. A result proven to have at most one row can omit ordering.
SKIS does not silently append keys. Grouped/derived keyset and computed keyset ordering remain
unsupported, including the first keyset request. See [pagination](pagination.md) for derived
identity propagation, continuation and explicit count-source requirements.

An empty-input global aggregate without HAVING returns one result row: COUNT's selected value is
zero and its Page total is one. An explicit grouped query on empty input returns no groups, even
when its grouping keys are constants. HAVING can remove a global row or a group. All-NULL SUM has
a NULL value; all-NULL COUNT has zero. These cases must not be conflated.

Page runs content and count on the same acquired connection without starting a transaction.
Callers needing a consistent snapshot supply the transaction isolation. Streams/cursors must be
closed on their creating thread; see [resource ownership](cursor-and-stream.md).

## Dialects, diagnostics and current limits

PostgreSQL/H2 explicitly declare aggregate, GROUP BY and HAVING capabilities. External dialects
must declare their actual capabilities and provide suitable lowering/type formats; using a custom
dialect ID does not select PostgreSQL or H2 behavior. Unsupported capabilities fail before JDBC.
See [dialect compilation](dialect-compilation.md).

| Code                                    | Meaning                                                                       |
|-----------------------------------------|-------------------------------------------------------------------------------|
| `SKISQ031` / `QUERY_GROUPING_CONTRACT`  | Invalid grouping key, duplicate resolved key or uncovered grouping dependency |
| `SKISQ032` / `QUERY_AGGREGATE_CONTRACT` | Aggregate clause, nesting or ownership violation                              |
| `SKISQ033` / `QUERY_GROUP_BY_CONTRACT`  | Invalid `groupBy` construction or repeated declaration                        |
| `SKISQ034` / `QUERY_HAVING_CONTRACT`    | Invalid HAVING construction or missing HAVING chain                           |

Errors retain structural reasons and block/clause positions without bound values.
See [runtime diagnostics](runtime-error-codes.md). Multi-expression COUNT DISTINCT, DISTINCT
SUM/AVG/MIN/MAX, FILTER, windows, ROLLUP/CUBE/GROUPING SETS and grouped/derived keyset are outside
this capability set. Generated keys, UPSERT, expanded batch writes, parameterized raw SQL and
driver fetch-size/true-streaming closure belong to the next writing milestone.

The [independent consumers](../skis-integration-tests/src/consumer/README.md) contain the full
entity, projection, dependency and test setup used by these examples. PR #91 merge commit `980ad24`
passed [CI](https://github.com/xhufang/skis/actions/runs/40284163961) and
[Compliance](https://github.com/xhufang/skis/actions/runs/40284163852): Java 21/25, PostgreSQL
container/URL paths, H2, independent consumers, cache/concurrency/resource contracts and engineering
gates. The push event skips dependency review;
[PR #91 Compliance](https://github.com/xhufang/skis/actions/runs/40281380091) supplies that evidence.
Documentation updates are statically checked locally; they are not new runtime evidence.
Benchmarks remain frozen throughout `0.2.x`.
