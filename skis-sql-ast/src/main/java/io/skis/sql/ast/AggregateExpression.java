package io.skis.sql.ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable single-value aggregate with a framework-owned result descriptor.
 *
 * <p>Factories validate local structure and types only. Query-block ownership, grouping, dialect
 * support and actual input mappings require the complete compilation pipeline. SQL NULL is ignored
 * by value aggregates; all aggregates except COUNT remain nullable even for a non-null input.
 *
 * @param <T> Java representation of the aggregate result
 */
public final class AggregateExpression<T> implements SqlExpression<T> {

  /** Aggregate operations in the initial portable subset. */
  public enum Kind {
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX
  }

  private static final AggregateExpression<Long> COUNT_ALL =
      new AggregateExpression<>(Kind.COUNT, false, null, Long.class);

  private final Kind kind;
  private final boolean distinct;
  private final @Nullable SqlExpression<?> operand;
  private final Class<T> javaType;
  private final SqlType sqlType;

  private AggregateExpression(
      Kind kind, boolean distinct, @Nullable SqlExpression<?> operand, Class<T> javaType) {
    this.sqlType = AggregateTypeRules.validate(kind, distinct, operand, javaType);
    this.kind = kind;
    this.distinct = distinct;
    this.operand = operand;
    this.javaType = javaType;
  }

  /** Counts all input rows, including duplicates introduced by joins; empty input yields zero. */
  public static AggregateExpression<Long> countAll() {
    return COUNT_ALL;
  }

  /** Counts non-NULL scalar values, including values with custom mappings. */
  public static AggregateExpression<Long> count(SqlExpression<?> operand) {
    return new AggregateExpression<>(
        Kind.COUNT, false, Objects.requireNonNull(operand, "operand"), Long.class);
  }

  /** Counts distinct non-NULL values in the built-in input subset. */
  public static AggregateExpression<Long> countDistinct(SqlExpression<?> operand) {
    return new AggregateExpression<>(
        Kind.COUNT, true, Objects.requireNonNull(operand, "operand"), Long.class);
  }

  /** Sums Byte, Short or Integer inputs as nullable BIGINT. */
  public static AggregateExpression<Long> sumAsLong(SqlExpression<? extends Number> operand) {
    return new AggregateExpression<>(
        Kind.SUM, false, Objects.requireNonNull(operand, "operand"), Long.class);
  }

  /** Sums Long or BigInteger inputs as nullable exact integral DECIMAL. */
  public static AggregateExpression<BigInteger> sumAsBigInteger(
      SqlExpression<? extends Number> operand) {
    return new AggregateExpression<>(
        Kind.SUM, false, Objects.requireNonNull(operand, "operand"), BigInteger.class);
  }

  /** Sums BigDecimal inputs without imposing a precision or scale. */
  public static AggregateExpression<BigDecimal> sumAsBigDecimal(
      SqlExpression<? extends Number> operand) {
    return new AggregateExpression<>(
        Kind.SUM, false, Objects.requireNonNull(operand, "operand"), BigDecimal.class);
  }

  /** Sums Float or Double inputs as nullable DOUBLE. */
  public static AggregateExpression<Double> sumAsDouble(SqlExpression<? extends Number> operand) {
    return new AggregateExpression<>(
        Kind.SUM, false, Objects.requireNonNull(operand, "operand"), Double.class);
  }

  /** Averages exact numeric inputs as nullable DECIMAL without Java-side rounding. */
  public static AggregateExpression<BigDecimal> avgAsBigDecimal(
      SqlExpression<? extends Number> operand) {
    return new AggregateExpression<>(
        Kind.AVG, false, Objects.requireNonNull(operand, "operand"), BigDecimal.class);
  }

  /** Averages Float or Double inputs as nullable DOUBLE. */
  public static AggregateExpression<Double> avgAsDouble(SqlExpression<? extends Number> operand) {
    return new AggregateExpression<>(
        Kind.AVG, false, Objects.requireNonNull(operand, "operand"), Double.class);
  }

  /** Returns the nullable minimum, preserving an allow-listed input's boxed Java and SQL types. */
  public static <V> AggregateExpression<V> min(SqlExpression<V> operand) {
    Objects.requireNonNull(operand, "operand");
    return new AggregateExpression<>(
        Kind.MIN, false, operand, SemanticValidator.boxedJavaType(operand.javaType()));
  }

  /** Returns the nullable maximum, preserving an allow-listed input's boxed Java and SQL types. */
  public static <V> AggregateExpression<V> max(SqlExpression<V> operand) {
    Objects.requireNonNull(operand, "operand");
    return new AggregateExpression<>(
        Kind.MAX, false, operand, SemanticValidator.boxedJavaType(operand.javaType()));
  }

  public Kind kind() {
    return kind;
  }

  /** Whether non-NULL inputs are deduplicated; only COUNT(expr) supports this flag. */
  public boolean distinct() {
    return distinct;
  }

  /** Empty only for COUNT(*); never contains an application value or SQL text. */
  public Optional<SqlExpression<?>> operand() {
    return Optional.ofNullable(operand);
  }

  /** Rebuilds an aggregate during structural lowering while retaining its fixed result contract. */
  public AggregateExpression<T> withOperand(SqlExpression<?> replacement) {
    Objects.requireNonNull(replacement, "replacement");
    if (operand == null) {
      throw new IllegalArgumentException("COUNT(*) has no operand to replace");
    }
    return replacement == operand
        ? this
        : new AggregateExpression<>(kind, distinct, replacement, javaType);
  }

  /**
   * Replaces an empty IN/NOT IN containing this block's aggregate without losing aggregation.
   *
   * <p>The result is constant even on empty input and retains no discarded columns or parameters.
   */
  public SqlPredicate emptyMembership(boolean negated) {
    if (kind == Kind.COUNT) {
      return new NullPredicate(
          countAll(), negated ? NullOperator.IS_NOT_NULL : NullOperator.IS_NULL);
    }
    SqlExpression<?> input = Objects.requireNonNull(operand, "aggregate operand");
    // Use one representative input domain for each fixed numeric result. This keeps the
    // constant witness identical whether numeric widening happened before or after pruning.
    Class<?> inputJavaType = input.javaType();
    SqlType inputSqlType = input.sqlType();
    if (kind == Kind.SUM || kind == Kind.AVG) {
      if (kind == Kind.SUM && javaType == Long.class) {
        inputJavaType = Integer.class;
      } else if (kind == Kind.SUM && javaType == BigInteger.class) {
        inputJavaType = Long.class;
      } else {
        inputJavaType = javaType;
      }
      inputSqlType = SqlType.fromJavaType(inputJavaType);
    }
    SqlExpression<?> typedNull =
        new CastExpression<>(
            LiteralExpression.nullLiteral(inputJavaType), inputJavaType, inputSqlType);
    return new NullPredicate(
        withOperand(typedNull), negated ? NullOperator.IS_NULL : NullOperator.IS_NOT_NULL);
  }

  @Override
  public Class<T> javaType() {
    return javaType;
  }

  @Override
  public SqlType sqlType() {
    return sqlType;
  }

  @Override
  public Nullability nullability() {
    return AggregateTypeRules.nullability(kind);
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return this == other
        || other instanceof AggregateExpression<?> aggregate
            && kind == aggregate.kind
            && distinct == aggregate.distinct
            && Objects.equals(operand, aggregate.operand)
            && javaType.equals(aggregate.javaType)
            && sqlType == aggregate.sqlType;
  }

  @Override
  public int hashCode() {
    int result = kind.hashCode();
    result = 31 * result + Boolean.hashCode(distinct);
    result = 31 * result + Objects.hashCode(operand);
    result = 31 * result + javaType.hashCode();
    return 31 * result + sqlType.hashCode();
  }
}
