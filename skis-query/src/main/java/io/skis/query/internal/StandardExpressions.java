package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryCondition;
import io.skis.query.QueryParameter;
import io.skis.query.Selectable;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Internal public-facade bridge; composition retains fragments without flattening child trees. */
public final class StandardExpressions {

  private static final NonNullSelectable<Long> COUNT_ALL =
      new NonNullExpressionSelectable<>(AggregateExpression.countAll());

  private StandardExpressions() {}

  public static NonNullSelectable<Long> countAll() {
    return COUNT_ALL;
  }

  public static <V> NonNullSelectable<V> nonNull(Selectable<V> expression) {
    if (expression instanceof NonNullSelectable<V> nonNull) {
      return nonNull;
    }
    throw new IllegalArgumentException("expression does not have a declared non-null result");
  }

  @SuppressWarnings("unchecked")
  public static <V> NonNullSelectable<V> value(V value) {
    Objects.requireNonNull(value, "value");
    return nonNull(value((Class<V>) value.getClass(), value));
  }

  public static <V> Selectable<V> value(Class<V> javaType, @Nullable V value) {
    QueryParameter<V> parameter =
        new DefaultQueryParameter<>(
            javaType, value == null ? Nullability.NULLABLE : Nullability.NON_NULL, null);
    SqlType sqlType = builtinType(parameter.javaType());
    if (value != null && value.getClass() != parameter.javaType()) {
      throw mismatch("value must have the exact declared boxed Java type");
    }
    return parameterValue(parameter, sqlType, QueryParameters.of(parameter, value));
  }

  public static <V> Selectable<V> value(QueryParameter<V> parameter) {
    Objects.requireNonNull(parameter, "parameter");
    if (!(parameter instanceof DefaultQueryParameter<?>)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_DECLARATION, "query parameter was not created by SKIS");
    }
    return parameterValue(parameter, builtinType(parameter.javaType()), QueryParameters.empty());
  }

  private static <V> Selectable<V> parameterValue(
      QueryParameter<V> parameter, SqlType sqlType, QueryParameters parameters) {
    SqlExpression<V> descriptor =
        new ParameterSlot<>(0, parameter.javaType(), sqlType, parameter.nullability());
    Selectable<V> source = new ExpressionSelectable<>(descriptor);
    return wrap(
        descriptor,
        new ExpressionSelectable.Recipe<>(
            (compiler, operands, condition) ->
                Objects.requireNonNull(compiler).parameter(source, parameter),
            List.of(),
            null,
            parameters));
  }

  public static <I, O> Selectable<O> unary(
      Selectable<I> expression, Function<SqlExpression<I>, SqlExpression<O>> factory) {
    return create(
        List.of(SelectableSupport.requireFramework(expression)),
        null,
        (compiler, operands, condition) -> factory.apply(operand(operands, 0)));
  }

  public static <O> Selectable<O> numericAggregate(
      Selectable<?> expression, Function<SqlExpression<Number>, SqlExpression<O>> factory) {
    return create(
        List.of(SelectableSupport.requireFramework(expression)),
        null,
        (compiler, operands, condition) -> factory.apply(operand(operands, 0)));
  }

  public static <N extends Number> Selectable<N> arithmetic(
      Selectable<N> left, ArithmeticOperator operator, Selectable<N> right) {
    return create(
        List.of(
            SelectableSupport.requireFramework(left), SelectableSupport.requireFramework(right)),
        null,
        (compiler, operands, condition) ->
            new ArithmeticExpression<>(operand(operands, 0), operator, operand(operands, 1)));
  }

  public static <V> Selectable<V> caseWhen(
      QueryCondition condition, Selectable<V> whenTrue, Selectable<V> whenFalse) {
    QueryConditions.framework(condition);
    return create(
        List.of(
            SelectableSupport.requireFramework(whenTrue),
            SelectableSupport.requireFramework(whenFalse)),
        condition,
        (compiler, operands, predicate) ->
            new CaseExpression<>(
                List.of(new CaseWhen<>(Objects.requireNonNull(predicate), operand(operands, 0))),
                StandardExpressions.operand(operands, 1)));
  }

  @SafeVarargs
  public static <V> Selectable<V> coalesce(Selectable<V>... expressions) {
    Objects.requireNonNull(expressions, "expressions");
    return create(
        Arrays.asList(expressions.clone()),
        null,
        (compiler, operands, condition) -> new CoalesceExpression<>(typedOperands(operands)));
  }

  @SafeVarargs
  public static Selectable<String> concat(Selectable<String>... expressions) {
    Objects.requireNonNull(expressions, "expressions");
    return create(
        Arrays.asList(expressions.clone()),
        null,
        (compiler, operands, condition) -> new ConcatExpression(typedOperands(operands)));
  }

  public static <V> Selectable<V> cast(
      Selectable<?> expression, Class<V> javaType, SqlType sqlType) {
    Class<V> boxed = new DefaultQueryParameter<>(javaType, Nullability.NULLABLE, null).javaType();
    Objects.requireNonNull(sqlType, "sqlType");
    if (builtinType(boxed) != sqlType) {
      throw mismatch("CAST target must match the exact built-in Java/SQL type mapping");
    }
    return create(
        List.of(SelectableSupport.requireFramework(expression)),
        null,
        (compiler, operands, condition) ->
            new CastExpression<>(operands.getFirst(), boxed, sqlType));
  }

  private static <V> Selectable<V> create(
      List<? extends Selectable<?>> operands,
      @Nullable QueryCondition condition,
      ExpressionSelectable.AstFactory<V> factory) {
    List<Selectable<?>> checked = new ArrayList<>(operands.size());
    List<SqlExpression<?>> descriptors = new ArrayList<>(operands.size());
    for (Selectable<?> operand : operands) {
      SelectableSupport.requireFramework(operand);
      checked.add(operand);
      descriptors.add(descriptor(operand, descriptors.size()));
    }
    // Constructors validate immediate descriptors. Full trees are compiled once per query block.
    SqlExpression<V> shape;
    try {
      shape =
          factory.create(
              null,
              descriptors,
              condition == null
                  ? null
                  : new NullPredicate(LiteralExpression.trueLiteral(), NullOperator.IS_NOT_NULL));
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_TYPE_MISMATCH, failure, "invalid standard expression type or shape");
    }
    return wrap(
        shape,
        new ExpressionSelectable.Recipe<>(factory, checked, condition, QueryParameters.empty()));
  }

  private static <V> SqlExpression<V> descriptor(Selectable<V> expression, int ordinal) {
    return new ParameterSlot<>(
        ordinal, expression.javaType(), expression.sqlType(), expression.nullability());
  }

  private static <V> ExpressionSelectable<V> wrap(
      SqlExpression<V> descriptor, ExpressionSelectable.Recipe<V> recipe) {
    return descriptor.nullable()
        ? new ExpressionSelectable<>(descriptor, recipe)
        : new NonNullExpressionSelectable<>(descriptor, recipe);
  }

  private static SqlType builtinType(Class<?> javaType) {
    SqlType type = SqlType.fromJavaType(javaType);
    if (type == SqlType.OTHER) {
      throw mismatch("Java type is not in the exact built-in value type whitelist");
    }
    return type;
  }

  @SuppressWarnings("unchecked")
  private static <V> SqlExpression<V> operand(List<SqlExpression<?>> operands, int index) {
    // All constructors validate the actual boxed Java/SQL descriptors, independently of erasure.
    return (SqlExpression<V>) operands.get(index);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <V> List<SqlExpression<V>> typedOperands(List<SqlExpression<?>> operands) {
    return (List) operands;
  }

  private static QueryValidationException mismatch(String detail) {
    return QueryValidationException.of(SkisErrorCode.QUERY_TYPE_MISMATCH, detail);
  }
}
