package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlPredicate;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Compiles the initial entity-select subset into immutable, value-independent JDBC plans. */
final class QueryPlanCompiler {

  private final Dialect dialect;

  QueryPlanCompiler(Dialect dialect) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
  }

  <E> CompiledQueryPlan<E, Object> compile(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable PropertyMeta<E, ?> equalityProperty) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(table, "table");
    if (table.entity() != model.entity()) {
      throw new QueryValidationException(
          "query table does not use the canonical runtime metadata of entity '"
              + model.entity().entityName()
              + "'");
    }

    SqlPredicate predicate = null;
    PropertyRuntime<E, ?> parameterRuntime = null;
    if (equalityProperty != null) {
      predicate = equalityPredicate(table, equalityProperty);
      parameterRuntime = model.property(equalityProperty);
    }
    RenderedSql rendered =
        dialect.renderer().render(new SelectStatement(table.selections(), table, predicate));
    if (rendered.parameterCount() != (parameterRuntime == null ? 0 : 1)) {
      throw new QueryValidationException(
          "dialect '"
              + dialect.id()
              + "' rendered an unexpected parameter shape for entity '"
              + model.entity().entityName()
              + "'");
    }
    PropertyRuntime<E, ?> runtime = parameterRuntime;
    return new CompiledQueryPlan<>(
        dialect.id(),
        rendered,
        (statement, firstIndex, argument, context) -> {
          Objects.requireNonNull(argument, "argument");
          if (runtime == null) {
            if (argument != NoParameters.INSTANCE) {
              throw new SQLException("compiled query does not accept parameters");
            }
            return firstIndex;
          }
          if (argument == NoParameters.INSTANCE) {
            throw new SQLException("compiled query requires one logical parameter");
          }
          runtime.bind(statement, firstIndex, argument, context);
          return firstIndex + 1;
        },
        model.fullRowDecoder());
  }

  private static <E, V> SqlPredicate equalityPredicate(
      QueryTable<E> table, PropertyMeta<E, V> property) {
    ColumnExpression<E, V> column = table.expression(property);
    return column.eq(new ParameterSlot<>(0, property.javaType(), false));
  }
}
