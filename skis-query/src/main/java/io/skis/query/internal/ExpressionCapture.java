package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.QueryCondition;
import io.skis.query.Selectable;
import java.util.IdentityHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Removes captured fragments once at a query boundary, retaining binding and reference identities.
 */
final class ExpressionCapture {

  private @Nullable IdentityHashMap<Selectable<?>, Selectable<?>> selections;
  private @Nullable IdentityHashMap<QueryCondition, QueryCondition> conditions;
  private QueryParameters firstParameters = QueryParameters.empty();
  private QueryParameters.@Nullable Builder parameters;

  static boolean capturesValues(Selectable<?> selectable) {
    return selectable instanceof ExpressionSelectable<?> expression && expression.capturesValues();
  }

  static boolean containsSubquery(Selectable<?> selectable) {
    return selectable instanceof ScalarSubquerySelectable<?>
        || (selectable instanceof ExpressionSelectable<?> expression
            && expression.containsSubquery());
  }

  static <S extends Selectable<?>> S reusable(S selectable) {
    SelectableSupport.requireFramework(selectable);
    if (capturesValues(selectable)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_CAPTURED_VALUE,
          "a reusable SELECT description cannot capture values; use Sql.parameter(...) instead");
    }
    return selectable;
  }

  @SuppressWarnings("unchecked")
  <V> Selectable<V> selectable(Selectable<V> selectable) {
    SelectableSupport.requireFramework(selectable);
    if (!capturesValues(selectable)) {
      return selectable;
    }
    IdentityHashMap<Selectable<?>, Selectable<?>> memo = selections;
    if (memo == null) {
      memo = new IdentityHashMap<>();
      selections = memo;
    }
    Selectable<?> existing = memo.get(selectable);
    if (existing == null) {
      existing = ((ExpressionSelectable<V>) selectable).capture(this);
      memo.put(selectable, existing);
    }
    return (Selectable<V>) existing;
  }

  QueryCondition condition(QueryCondition condition) {
    FrameworkQueryCondition framework = QueryConditions.framework(condition);
    if (!framework.capturesValues()) {
      return condition;
    }
    if (!framework.capturesExpressionValues()) {
      return framework.capture(this);
    }
    IdentityHashMap<QueryCondition, QueryCondition> memo = conditions;
    if (memo == null) {
      memo = new IdentityHashMap<>();
      conditions = memo;
    }
    QueryCondition existing = memo.get(condition);
    if (existing == null) {
      existing = framework.capture(this);
      memo.put(condition, existing);
    }
    return existing;
  }

  void include(QueryParameters included) {
    if (included.isEmpty() || included == firstParameters) {
      return;
    }
    QueryParameters.Builder builder = parameters;
    if (builder == null && firstParameters.isEmpty()) {
      firstParameters = included;
      return;
    }
    if (builder == null) {
      builder = QueryParameters.builder();
      builder.include(firstParameters);
      parameters = builder;
    }
    builder.include(included);
  }

  QueryParameters parameters() {
    QueryParameters.Builder builder = parameters;
    return builder == null ? firstParameters : builder.build();
  }
}
