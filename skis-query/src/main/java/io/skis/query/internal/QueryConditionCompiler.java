package io.skis.query.internal;

import io.skis.query.Selectable;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import java.util.IdentityHashMap;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One query-block compiler backed by the statement layout shared across SQL clauses. */
final class QueryConditionCompiler {

  private final StatementParameterLayout layout;
  private final QueryParameterBindings bindings;
  private final StatementParameterLayout.QueryBlock queryBlock;
  private @Nullable IdentityHashMap<Object, SqlExpression<?>> expressions;

  QueryConditionCompiler(StatementParameterLayout layout, QueryParameterBindings bindings) {
    this.layout = Objects.requireNonNull(layout, "layout");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.queryBlock = layout.newQueryBlock();
  }

  <V> ParameterSlot<V> parameter(Selectable<V> source, QueryParameter<V> parameter) {
    return queryBlock.parameter(source, parameter);
  }

  boolean releaseOriginalExpressions() {
    return !layout.retainOriginalExpressions();
  }

  void recordSelectedOrdering() {
    layout.recordSelectedOrdering();
  }

  void recordEmptyMembership() {
    layout.recordEmptyMembership();
  }

  <V> SqlExpression<V> expression(Selectable<V> selectable) {
    Objects.requireNonNull(selectable, "selectable");
    if (selectable instanceof ScalarSubquerySelectable<?> scalar) {
      return compileScalar(scalar);
    }
    if (selectable instanceof ExpressionSelectable<V> standard) {
      if (standard.decomposed()) {
        return standard.expression();
      }
      if (standard.containsSubquery()) {
        return standard.compile(this);
      }
      IdentityHashMap<Object, SqlExpression<?>> memo = expressions;
      if (memo == null) {
        memo = new IdentityHashMap<>();
        expressions = memo;
      }
      Object identity = standard.compilationIdentity();
      SqlExpression<?> existing = memo.get(identity);
      if (existing == null) {
        existing = standard.compile(this);
        memo.put(identity, existing);
      }
      @SuppressWarnings("unchecked")
      SqlExpression<V> typed = (SqlExpression<V>) existing;
      return typed;
    }
    return SelectableSupport.expression(selectable);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private <V> SqlExpression<V> compileScalar(ScalarSubquerySelectable<?> scalar) {
    return ((ScalarSubquerySelectable) scalar).compile(this);
  }

  void include(QueryParameters included) {
    bindings.include(included);
  }

  SelectStatement subquery(SelectQueryState<?> state) {
    return QueryStructureCompiler.compileSubquery(
        Objects.requireNonNull(state, "state"), layout, bindings);
  }
}
