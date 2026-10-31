package io.skis.query.internal;

import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Package-owned adapter from a reusable one-column SELECT to a nullable SQL value expression. */
final class ScalarSubquerySelectable<V> implements Selectable<V> {

  private final SelectQueryState<?> subquery;
  private final QueryParameters parameters;
  private final Selectable<V> output;
  private final ScalarSubqueryExpression<V> expression;
  private final List<QueryParameter<?>> parameterReferences;

  ScalarSubquerySelectable(SingleColumnQuery<? extends @Nullable V> subquery) {
    SingleColumnQuery<? extends @Nullable V> description =
        Objects.requireNonNull(subquery, "subquery");
    this.subquery = QueryModels.description(description).state();
    this.parameters = QueryModels.description(description).parameters;
    this.output = typedOutput(description);
    StatementParameterLayout layout = new StatementParameterLayout();
    this.expression =
        new ScalarSubqueryExpression<>(
            QueryStructureCompiler.compileSubquery(
                this.subquery, layout, new QueryParameterBindings()));
    this.parameterReferences = layout.parameterReferences();
  }

  @SuppressWarnings("unchecked")
  private static <V> Selectable<V> typedOutput(
      SingleColumnQuery<? extends @Nullable V> description) {
    // The description is created from one scalar SelectedResult<V>; only its storage is erased.
    return (Selectable<V>) QueryModels.description(description).state.selected().singleSelectable();
  }

  private ScalarSubquerySelectable(ScalarSubquerySelectable<V> source) {
    this.subquery = source.subquery;
    this.parameters = QueryParameters.empty();
    this.output = source.output;
    this.expression = source.expression;
    this.parameterReferences = source.parameterReferences;
  }

  boolean capturesValues() {
    return !parameters.isEmpty();
  }

  ScalarSubquerySelectable<V> capture(ExpressionCapture capture) {
    capture.include(parameters);
    return parameters.isEmpty() ? this : new ScalarSubquerySelectable<>(this);
  }

  @Override
  public Class<V> javaType() {
    return output.javaType();
  }

  @Override
  public SqlType sqlType() {
    return output.sqlType();
  }

  @Override
  public Nullability nullability() {
    return Nullability.NULLABLE;
  }

  SqlExpression<V> expression() {
    return expression;
  }

  Selectable<V> output() {
    return output;
  }

  List<QueryParameter<?>> parameterReferences() {
    return parameterReferences;
  }

  ScalarSubqueryExpression<V> compile(QueryConditionCompiler compiler) {
    compiler.include(parameters);
    return new ScalarSubqueryExpression<>(compiler.subquery(subquery));
  }
}
