# SQL expressions and semantic validation

This document describes the expression contract implemented by the internal `0.2.2-SNAPSHOT` and
`0.2.3-SNAPSHOT` milestones. It accumulates toward the public `0.3.0` SQL DSL and is not part of
the published `0.2.0` API.

## Expression descriptors

Every `SqlExpression<T>` carries three independent pieces of metadata:

- the Java representation returned by `javaType()`;
- a portable `SqlType`, used before dialect rendering;
- explicit `Nullability`, propagated using SQL three-valued semantics.

Predicates always use `Boolean`/`BOOLEAN`. Ordinary comparisons become nullable when either
operand can be SQL `NULL`; `IS NULL` and `IS NOT NULL` are always non-null booleans. `COALESCE` is
non-null when at least one operand is non-null. An implicit `CASE` ELSE is SQL `NULL`.

## Portable expression nodes

The initial cross-dialect set contains:

- `ParameterSlot<T>` for all ordinary application values;
- allow-listed `LiteralExpression<T>` values: `NULL`, `TRUE`, `FALSE`, numeric `0`, and numeric `1`;
- `ArithmeticExpression<T>` with add, subtract, multiply, and divide;
- `ConcatExpression` using SQL-standard character concatenation;
- searched `CaseExpression<T>` and `CaseWhen<T>` branches;
- `CastExpression<T>` for the portable PostgreSQL/H2 target subset;
- `CoalesceExpression<T>` with two or more compatible operands.

`IncrementExpression<T>` remains as the specialized version-column `+ 1` node used by the
mutation Fast Path. General arithmetic should use `ArithmeticExpression<T>`.

`BigInteger` division is rejected because both baseline databases implement it through SQL
`DECIMAL` division, whose result may have a fractional part that cannot be decoded exactly as a
`BigInteger`. Cast both operands to `BigDecimal` before division when fractional results are
required. Addition, subtraction, and multiplication retain `BigInteger` result semantics.

Portable CAST targets cover boolean, numeric, character, UUID, date, time, and timestamp types.
`TINYINT` is rendered as the PostgreSQL/H2 common `SMALLINT` target. `VARBINARY` and `OTHER` are
not exposed as portable CAST targets because the two baseline dialects do not share a safe type
name and conversion contract for them.

Application data must never be encoded as a literal. `LiteralExpression` has no arbitrary string
or value constructor; application values remain outside AST equality and enter SQL through
`ParameterSlot` and JDBC binding. The following low-level AST contains five parameters even when
the values change between executions:

```java
SelectStatement statement =
    new SelectStatement(
        List.of(
            new ArithmeticExpression<>(pet.id(), ArithmeticOperator.ADD, idOffset),
            new ConcatExpression(List.of(pet.name(), suffix)),
            new CaseExpression<>(
                List.of(new CaseWhen<>(pet.id().gt(threshold), pet.name())),
                otherwiseName),
            new CastExpression<>(pet.id(), String.class),
            new CoalesceExpression<>(List.of(pet.name(), fallbackName))),
        pet);
```

The corresponding PostgreSQL/H2 shape is:

```sql
SELECT ("pet"."id" + ?),
       ("pet"."pet_name" || ?),
       CASE WHEN "pet"."id" > ? THEN "pet"."pet_name" ELSE ? END,
       CAST("pet"."id" AS VARCHAR),
       COALESCE("pet"."pet_name", ?)
FROM "shelter"."pet"
```

## Central validation boundary

`SemanticValidator` is the single rule owner for current portable expression semantics. Node
constructors delegate their local compatibility checks to it, while statement construction adds
scope, parameter-shape, and mutation checks.

Before rendering, validation rejects:

- incompatible Java or SQL types for comparisons, ranges, membership, arithmetic, CASE, and
  COALESCE;
- `BigInteger` division whose possible fractional SQL result cannot satisfy its Java result type;
- ordered operators on non-orderable types and `LIKE`/concatenation on non-character types;
- ordinary comparison with an expression that is statically known to be SQL `NULL`;
- a column from another table expression or alias, including references nested inside CASE,
  arithmetic, concatenation, casts, and COALESCE;
- conflicting descriptors for a repeated parameter ordinal, or a gap in zero-based ordinals;
- columns outside the mutation target, writes to read-only entities, non-insertable/non-updatable
  columns, and nullable assignments to non-null columns.

Custom opaque expression implementations remain value-only leaves to preserve the current
extension contract. Their Java/SQL/nullability descriptors are validated, but the AST does not yet
define a public child-traversal SPI. Such a node cannot claim portable nested scope or parameter
validation and is still rejected by `StandardSqlRenderer`; a future traversal SPI would require a
separate architecture decision.

INSERT values have no visible table-column scope. UPDATE expressions and predicates may reference
only the target table expression. DELETE predicates may reference only the target. SELECT currently
has exactly one visible FROM table expression.

Subqueries, derived tables, joins, and CTEs are not represented by the `0.2.2` AST. Their outer and
inner visibility rules will be added with those nodes in later `0.2.x` milestones; the validator
does not claim to validate syntax that the AST cannot yet express.

## SELECT ordering, pagination and count

The `0.2.3` SELECT structure adds visible and hidden selections, `distinct`, ordered expressions,
and parameterized `Limit`, `OffsetLimit` or `KeysetSeek` pagination. `CountAst` is independent: it
contains only its source, predicate and optional distinct expression, so count rendering cannot be
implemented by deleting fragments from a content SQL string. A nullable distinct expression adds a
null-presence term because `SELECT DISTINCT` returns one `NULL` row while SQL `COUNT(DISTINCT ...)`
does not count `NULL`.

Structural equality includes direction, null placement, hidden selections and pagination parameter
descriptors, but not bound limit, offset, predicate or keyset values. Repeated parameter ordinals
are legal when their Java type, SQL type and nullability descriptors agree; this permits a typed
keyset anchor to appear in multiple branches of a lexicographic seek predicate.

`SemanticValidator` checks that order and hidden expressions belong to the single visible table,
pagination slots use the required non-null integer/long descriptors, offset/keyset pagination has
an order, and all parameter ordinals remain dense. Dialect rendering then requires explicit
parameterized limit/offset capabilities and either uses native null ordering or a semantically
equivalent `CASE` fallback.
