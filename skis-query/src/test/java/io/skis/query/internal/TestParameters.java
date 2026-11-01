package io.skis.query.internal;

import io.skis.query.*;
import io.skis.sql.ast.*;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Test-owned access to logical parameter identity; none of this template API is public production DSL. */
final class TestParameters {
  private static final java.lang.reflect.Method COMPILATION = method(
      "compilation", QueryPagination.class, io.skis.core.internal.OperationObservation.class,
      io.skis.core.spi.StatementPurpose.class);
  private static final java.lang.reflect.Method QUERY_FINGERPRINT = method(
      "queryFingerprint", io.skis.core.internal.OperationObservation.class);

  private TestParameters() {}

  private static java.lang.reflect.Method method(String name, Class<?>... parameterTypes) {
    try {
      var method = DefaultExecutableSelectQuery.class.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  /** Test-only access to the actual terminal compilation path, without a production test hook. */
  @SuppressWarnings("unchecked")
  static <R extends @Nullable Object> QueryCompilation<R> compilation(
      ExecutableSelectQuery<R> query, QueryPagination pagination) {
    return (QueryCompilation<R>) invoke(
        COMPILATION, query, pagination, null, io.skis.core.spi.StatementPurpose.PRIMARY);
  }

  static String queryFingerprint(ExecutableSelectQuery<?> query) {
    return (String) invoke(QUERY_FINGERPRINT, query, (Object) null);
  }

  private static Object invoke(
      java.lang.reflect.Method method, ExecutableSelectQuery<?> query, @Nullable Object... arguments) {
    try {
      return Objects.requireNonNull(method.invoke(query, arguments));
    } catch (java.lang.reflect.InvocationTargetException failure) {
      Throwable cause = Objects.requireNonNull(failure.getCause());
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new AssertionError(cause);
    } catch (IllegalAccessException failure) {
      throw new AssertionError(failure);
    }
  }

  static QueryCompilation<Long> countCompilation(ExecutableSelectQuery<?> query) {
    return ((DefaultExecutableSelectQuery<?>) query).countCompilation(null);
  }
  static <V> QueryParameter<V> parameter(Class<V> type) { return parameter(type, Nullability.NULLABLE); }
  static <V> QueryParameter<V> parameter(Class<V> type, Nullability nullable) { return new DefaultQueryParameter<>(type, nullable); }
  static <V> Selectable<V> value(QueryParameter<V> parameter) {
    SqlType type = SqlType.fromJavaType(parameter.javaType());
    if (type == SqlType.OTHER) throw io.skis.core.QueryValidationException.of(io.skis.core.SkisErrorCode.QUERY_TYPE_MISMATCH, "no built-in codec for logical parameter");
    var descriptor = new ParameterSlot<>(0, parameter.javaType(), type, parameter.nullability());
    Selectable<V> source = new ExpressionSelectable<>(descriptor);
    var recipe = new ExpressionSelectable.Recipe<V>((compiler, operands, condition) -> Objects.requireNonNull(compiler).parameter(source, parameter), List.of(), null, QueryParameters.empty());
    return parameter.nullability().isNullable() ? new ExpressionSelectable<>(descriptor, recipe) : new NonNullExpressionSelectable<>(descriptor, recipe);
  }
  @SuppressWarnings("unchecked")
  static <R extends @Nullable Object, V, Q extends SelectSource<R>> Q bind(
      Q source, QueryParameter<V> parameter, @Nullable V value) {
    var query = QueryModels.<R>description(source);
    boolean declared = query.state.structure().validationStructure().parameterReferences().stream().anyMatch(reference -> reference == parameter);
    if (!declared) throw io.skis.core.QueryValidationException.of(io.skis.core.SkisErrorCode.QUERY_PARAMETER_DECLARATION, "logical parameter is absent from the query");
    var captured = QueryParameters.builder();
    captured.include(query.parameters);
    captured.bind(parameter, value);
    return (Q) query.recreate(query.state, captured.build(), query.orderingOnlyParameters);
  }
  static <R extends @Nullable Object> DefaultExecutableSelectQuery<R> query(QueryOperations operations, SelectSource<R> model) {
    return ((DefaultQueryOperations) operations).executable(QueryModels.description(model), io.skis.core.ExecutionOptions.NONE);
  }
  static QueryCompilation<Long> countCompilation(CountQuery source) {
    var count = (DefaultCountQuery) source;
    var operations = Objects.requireNonNull(count.operations(), "count query requires an execution scope");
    return count.compilation(operations, count.executionContext(), null);
  }
  static <V> QueryCondition eq(Selectable<V> column, QueryParameter<V> value) { return comparison(column, ComparisonOperator.EQUAL, value); }
  static <V> QueryCondition ne(Selectable<V> column, QueryParameter<V> value) { return comparison(column, ComparisonOperator.NOT_EQUAL, value); }
  static <V> QueryCondition gt(Selectable<V> column, QueryParameter<V> value) { return comparison(column, ComparisonOperator.GREATER_THAN, value); }
  static <V> QueryCondition ge(Selectable<V> column, QueryParameter<V> value) { return comparison(column, ComparisonOperator.GREATER_THAN_OR_EQUAL, value); }
  static <V> QueryCondition lt(Selectable<V> column, QueryParameter<V> value) { return comparison(column, ComparisonOperator.LESS_THAN, value); }
  static <V> QueryCondition le(Selectable<V> column, QueryParameter<V> value) { return comparison(column, ComparisonOperator.LESS_THAN_OR_EQUAL, value); }
  private static <V> QueryCondition comparison(Selectable<V> column, ComparisonOperator operator, QueryParameter<V> value) {
    SelectableSupport.expressionComparison(column, operator, column);
    requireParameterType(column, value);
    return FrameworkQueryCondition.parameterComparison(column, operator, value, QueryParameters.empty());
  }
  static <V> QueryCondition like(Selectable<V> column, QueryParameter<V> value) {
    SelectableSupport.like(column, "");
    requireParameterType(column, value);
    return FrameworkQueryCondition.likeParameter(column, value, QueryParameters.empty());
  }
  static <V> QueryCondition between(Selectable<V> column, QueryParameter<V> lower, QueryParameter<V> upper) {
    SelectableSupport.expressionComparison(column, ComparisonOperator.LESS_THAN_OR_EQUAL, column);
    requireParameterType(column, lower); requireParameterType(column, upper);
    return FrameworkQueryCondition.betweenParameters(column, lower, upper, QueryParameters.empty());
  }
  static <V> QueryCondition inParameters(Selectable<V> column, Collection<? extends QueryParameter<V>> values) { return membership(column, values, false); }
  static <V> QueryCondition notInParameters(Selectable<V> column, Collection<? extends QueryParameter<V>> values) { return membership(column, values, true); }
  private static <V> QueryCondition membership(Selectable<V> column, Collection<? extends QueryParameter<V>> values, boolean negated) {
    SelectableSupport.membership(column, List.of(), negated);
    List<QueryParameter<V>> parameters = new ArrayList<>(values.size());
    for (QueryParameter<V> value : values) { requireParameterType(column, value); parameters.add(value); }
    return FrameworkQueryCondition.membershipParameters(column, parameters, negated, QueryParameters.empty());
  }
  private static void requireParameterType(Selectable<?> column, QueryParameter<?> parameter) {
    Objects.requireNonNull(parameter, "parameter");
    if (!(parameter instanceof DefaultQueryParameter<?>)) throw io.skis.core.QueryValidationException.of(
        io.skis.core.SkisErrorCode.QUERY_PARAMETER_DECLARATION, "query parameter was not created by SKIS");
    if (!column.javaType().equals(parameter.javaType())) throw io.skis.core.QueryValidationException.of(
        io.skis.core.SkisErrorCode.QUERY_PARAMETER_VALUE, "logical parameter type does not match its expression");
  }
}
