package io.skis.query;

import io.skis.query.internal.StandardExpressions;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.SqlType;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.jspecify.annotations.Nullable;

/** Static factories for immutable, framework-owned SQL descriptions. */
public final class Sql {

  private Sql() {}

  /** Counts all input rows, including join duplicates; empty input produces zero. */
  public static NonNullSelectable<Long> countAll() {
    return StandardExpressions.countAll();
  }

  /**
   * Creates the typed SQL COUNT expression; final scope and dialect checks occur during
   * compilation.
   */
  public static NonNullSelectable<Long> count(Selectable<?> expression) {
    return StandardExpressions.nonNull(
        StandardExpressions.unary(expression, AggregateExpression::count));
  }

  /**
   * Creates the typed SQL COUNT DISTINCT expression; final scope and dialect checks occur during
   * compilation.
   */
  public static NonNullSelectable<Long> countDistinct(Selectable<?> expression) {
    return StandardExpressions.nonNull(
        StandardExpressions.unary(expression, AggregateExpression::countDistinct));
  }

  /**
   * Creates the typed SQL MIN expression; final scope and dialect checks occur during compilation.
   */
  public static <V> Selectable<V> min(Selectable<V> expression) {
    return StandardExpressions.unary(expression, AggregateExpression::min);
  }

  /**
   * Creates the typed SQL MAX expression; final scope and dialect checks occur during compilation.
   */
  public static <V> Selectable<V> max(Selectable<V> expression) {
    return StandardExpressions.unary(expression, AggregateExpression::max);
  }

  /** Creates a nullable aggregate with a fixed Long result and an exact input whitelist. */
  public static Selectable<Long> sumAsLong(Selectable<? extends Number> expression) {
    return StandardExpressions.numericAggregate(expression, AggregateExpression::sumAsLong);
  }

  /** Creates a nullable aggregate with a fixed BigInteger result and an exact input whitelist. */
  public static Selectable<BigInteger> sumAsBigInteger(
      Selectable<? extends Number> expression) {
    return StandardExpressions.numericAggregate(expression, AggregateExpression::sumAsBigInteger);
  }

  /** Creates a nullable aggregate with a fixed BigDecimal result and an exact input whitelist. */
  public static Selectable<BigDecimal> sumAsBigDecimal(
      Selectable<? extends Number> expression) {
    return StandardExpressions.numericAggregate(expression, AggregateExpression::sumAsBigDecimal);
  }

  /** Creates a nullable aggregate with a fixed Double result and an exact input whitelist. */
  public static Selectable<Double> sumAsDouble(Selectable<? extends Number> expression) {
    return StandardExpressions.numericAggregate(expression, AggregateExpression::sumAsDouble);
  }

  /** Creates a nullable aggregate with a fixed BigDecimal result and an exact input whitelist. */
  public static Selectable<BigDecimal> avgAsBigDecimal(
      Selectable<? extends Number> expression) {
    return StandardExpressions.numericAggregate(expression, AggregateExpression::avgAsBigDecimal);
  }

  /** Creates a nullable aggregate with a fixed Double result and an exact input whitelist. */
  public static Selectable<Double> avgAsDouble(Selectable<? extends Number> expression) {
    return StandardExpressions.numericAggregate(expression, AggregateExpression::avgAsDouble);
  }

  /** Captures one non-null built-in value once as an anonymous logical parameter. */
  public static <V> NonNullSelectable<V> value(V value) {
    return StandardExpressions.value(value);
  }

  /**
   * Captures an exactly typed built-in value, including typed NULL; the static result is
   * conservative.
   */
  public static <V> Selectable<V> value(Class<V> javaType, @Nullable V value) {
    return StandardExpressions.value(javaType, value);
  }

  /** Views an existing logical parameter as an expression without binding a value. */
  public static <V> Selectable<V> value(QueryParameter<V> parameter) {
    return StandardExpressions.value(parameter);
  }

  /** Applies SQL ADD with identical numeric types and no implicit widening. */
  public static <N extends Number> Selectable<N> add(Selectable<N> left, Selectable<N> right) {
    return StandardExpressions.arithmetic(left, ArithmeticOperator.ADD, right);
  }

  /** Applies SQL ADD with identical numeric types and no implicit widening. */
  public static <N extends Number> NonNullSelectable<N> add(
      NonNullSelectable<N> left, NonNullSelectable<N> right) {
    return StandardExpressions.nonNull(
        StandardExpressions.arithmetic(left, ArithmeticOperator.ADD, right));
  }

  /** Applies SQL SUBTRACT with identical numeric types and no implicit widening. */
  public static <N extends Number> Selectable<N> subtract(Selectable<N> left, Selectable<N> right) {
    return StandardExpressions.arithmetic(left, ArithmeticOperator.SUBTRACT, right);
  }

  /** Applies SQL SUBTRACT with identical numeric types and no implicit widening. */
  public static <N extends Number> NonNullSelectable<N> subtract(
      NonNullSelectable<N> left, NonNullSelectable<N> right) {
    return StandardExpressions.nonNull(
        StandardExpressions.arithmetic(left, ArithmeticOperator.SUBTRACT, right));
  }

  /** Applies SQL MULTIPLY with identical numeric types and no implicit widening. */
  public static <N extends Number> Selectable<N> multiply(Selectable<N> left, Selectable<N> right) {
    return StandardExpressions.arithmetic(left, ArithmeticOperator.MULTIPLY, right);
  }

  /** Applies SQL MULTIPLY with identical numeric types and no implicit widening. */
  public static <N extends Number> NonNullSelectable<N> multiply(
      NonNullSelectable<N> left, NonNullSelectable<N> right) {
    return StandardExpressions.nonNull(
        StandardExpressions.arithmetic(left, ArithmeticOperator.MULTIPLY, right));
  }

  /** Applies SQL DIVIDE with identical numeric types and no implicit widening. */
  public static <N extends Number> Selectable<N> divide(Selectable<N> left, Selectable<N> right) {
    return StandardExpressions.arithmetic(left, ArithmeticOperator.DIVIDE, right);
  }

  /** Applies SQL DIVIDE with identical numeric types and no implicit widening. */
  public static <N extends Number> NonNullSelectable<N> divide(
      NonNullSelectable<N> left, NonNullSelectable<N> right) {
    return StandardExpressions.nonNull(
        StandardExpressions.arithmetic(left, ArithmeticOperator.DIVIDE, right));
  }

  /** Creates searched CASE; FALSE and UNKNOWN select the otherwise branch. */
  public static <V> Selectable<V> caseWhen(
      QueryCondition condition, Selectable<V> whenTrue, Selectable<V> whenFalse) {
    return StandardExpressions.caseWhen(condition, whenTrue, whenFalse);
  }

  /** Creates CASE with two declared non-null result branches. */
  public static <V> NonNullSelectable<V> caseWhen(
      QueryCondition condition, NonNullSelectable<V> whenTrue, NonNullSelectable<V> whenFalse) {
    return StandardExpressions.nonNull(
        StandardExpressions.caseWhen(condition, whenTrue, whenFalse));
  }

  /** Returns the first non-NULL operand; requires at least two compatible result expressions. */
  @SafeVarargs
  public static <V> Selectable<V> coalesce(Selectable<V>... expressions) {
    return StandardExpressions.coalesce(expressions);
  }

  /**
   * Uses a declared non-null fallback; effective outer-join nullability is checked at compilation.
   */
  public static <V> NonNullSelectable<V> coalesce(
      Selectable<V> expression, NonNullSelectable<V> fallback) {
    return StandardExpressions.nonNull(StandardExpressions.coalesce(expression, fallback));
  }

  /** Concatenates at least two String expressions; any NULL operand propagates NULL. */
  @SafeVarargs
  public static Selectable<String> concat(Selectable<String>... expressions) {
    return StandardExpressions.concat(expressions);
  }

  /** Concatenates at least two declared non-null String expressions. */
  @SafeVarargs
  public static NonNullSelectable<String> concat(NonNullSelectable<String>... expressions) {
    return StandardExpressions.nonNull(StandardExpressions.concat(expressions));
  }

  /** Explicitly casts to an exact built-in Java/SQL target using portable conversion rules. */
  public static <V> Selectable<V> cast(
      Selectable<?> expression, Class<V> javaType, SqlType sqlType) {
    return StandardExpressions.cast(expression, javaType, sqlType);
  }

  /** Preserves declared non-nullness across an explicit portable CAST. */
  public static <V> NonNullSelectable<V> cast(
      NonNullSelectable<?> expression, Class<V> javaType, SqlType sqlType) {
    return StandardExpressions.nonNull(StandardExpressions.cast(expression, javaType, sqlType));
  }

  /** Selects a complete entity before an independent of root is chosen. */
  public static <E> SelectFromStep<SelectDescription<E>> select(QueryTable<E> table) {
    return io.skis.query.internal.QueryDsl.select(table);
  }

  /** Selects one declared non-null SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<V>> select(NonNullSelectable<V> selectable) {
    return io.skis.query.internal.QueryDsl.select(selectable);
  }

  /** Selects one conservatively nullable SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> select(
      Selectable<V> selectable) {
    return io.skis.query.internal.QueryDsl.select(selectable);
  }

  /** Selects one generated, fixed-arity result-row shape. */
  public static <R> SelectFromStep<SelectDescription<R>> select(ProjectionSelection<R> projection) {
    return io.skis.query.internal.QueryDsl.select(projection);
  }

  /** Explicitly permits a complete entity selection to become nullable after outer joins. */
  public static <E> SelectFromStep<SelectDescription<@Nullable E>> selectNullable(
      QueryTable<E> table) {
    return io.skis.query.internal.QueryDsl.selectNullable(table);
  }

  /** Explicitly permits a declared non-null value to become nullable in query context. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    return io.skis.query.internal.QueryDsl.selectNullable(selectable);
  }

  /** Creates the complete-entity SELECT/FROM convenience description. */
  public static <E> SelectDescription<E> selectFrom(QueryTable<E> table) {
    return io.skis.query.internal.QueryDsl.selectFrom(table);
  }

  public static <V> NonNullDerivedOutput<V> output(NonNullSelectable<V> selectable, String alias) {
    return io.skis.query.internal.QueryDsl.output(selectable, alias);
  }

  public static <V> DerivedOutput<V> output(Selectable<V> selectable, String alias) {
    return io.skis.query.internal.QueryDsl.output(selectable, alias);
  }

  public static <V> DerivedOutput<V> outputNullable(NonNullSelectable<V> selectable, String alias) {
    return io.skis.query.internal.QueryDsl.outputNullable(selectable, alias);
  }

  public static DerivedRelation derived(
      SelectDescription<?> description, String alias, DerivedOutput<?>... outputs) {
    return io.skis.query.internal.QueryDsl.derived(description, alias, outputs);
  }

  public static QueryCondition exists(SelectDescription<?> description) {
    return io.skis.query.internal.QueryDsl.exists(description);
  }

  public static QueryCondition notExists(SelectDescription<?> description) {
    return io.skis.query.internal.QueryDsl.notExists(description);
  }

  public static <V> Selectable<V> scalar(SingleColumnSelect<V> subquery) {
    return io.skis.query.internal.QueryDsl.scalar(subquery);
  }

  public static <V> QueryParameter<V> parameter(Class<V> javaType) {
    return io.skis.query.internal.QueryDsl.parameter(javaType);
  }

  public static <V> QueryParameter<V> parameter(Class<V> javaType, String diagnosticName) {
    return io.skis.query.internal.QueryDsl.parameter(javaType, diagnosticName);
  }
}
