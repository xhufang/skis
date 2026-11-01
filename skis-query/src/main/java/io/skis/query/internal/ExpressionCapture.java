package io.skis.query.internal;

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
  private QueryParameters parameters = QueryParameters.empty();

  static boolean capturesValues(Selectable<?> selectable) {
    return (selectable instanceof ExpressionSelectable<?> expression && expression.capturesValues())
        || (selectable instanceof ScalarSubquerySelectable<?> scalar && scalar.capturesValues());
  }

  static boolean containsSubquery(Selectable<?> selectable) {
    return selectable instanceof ScalarSubquerySelectable<?>
        || (selectable instanceof ExpressionSelectable<?> expression
            && expression.containsSubquery());
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
      existing =
          selectable instanceof ScalarSubquerySelectable<?> scalar
              ? ((ScalarSubquerySelectable<V>) scalar).capture(this)
              : ((ExpressionSelectable<V>) selectable).capture(this);
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

  /**
   * Records captured bindings as an O(1) immutable composite; duplicate bindings are rejected when
   * the environment is first consumed.
   */
  void include(QueryParameters included) {
    parameters = parameters.merge(included);
  }

  QueryParameters parameters() {
    return parameters;
  }
}
