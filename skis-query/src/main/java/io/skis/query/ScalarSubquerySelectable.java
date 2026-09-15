package io.skis.query;

import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.util.List;
import java.util.Objects;

/** Package-owned adapter from a reusable one-column SELECT to a nullable SQL value expression. */
final class ScalarSubquerySelectable<V> implements Selectable<V> {

  private final SelectQueryState<V> subquery;
  private final Selectable<V> output;
  private final ScalarSubqueryExpression<V> expression;
  private final List<QueryParameter<?>> parameterReferences;

  ScalarSubquerySelectable(SingleColumnSelect<V> subquery) {
    SingleColumnSelect<V> description = Objects.requireNonNull(subquery, "subquery");
    this.subquery = description.state();
    this.output = description.valueSelectable();
    StatementParameterLayout layout = new StatementParameterLayout();
    this.expression =
        new ScalarSubqueryExpression<>(
            QueryStructureCompiler.compileSubquery(
                this.subquery, layout, new QueryParameterBindings()));
    this.parameterReferences = layout.parameterReferences();
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

  @Override
  public SqlExpression<V> expression() {
    return expression;
  }

  SelectQueryState<V> subquery() {
    return subquery;
  }

  Selectable<V> output() {
    return output;
  }

  List<QueryParameter<?>> parameterReferences() {
    return parameterReferences;
  }

  ScalarSubqueryExpression<V> compile(QueryConditionCompiler compiler) {
    return new ScalarSubqueryExpression<>(compiler.subquery(subquery));
  }
}
