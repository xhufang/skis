package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowDecoder;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
    return compile(model, table, table.selections(), equalityProperty, model.fullRowDecoder());
  }

  <E, R> CompiledQueryPlan<R, Object> compileProjection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable PropertyMeta<E, ?> equalityProperty) {
    Objects.requireNonNull(projection, "projection").validateFrom(table);
    List<SqlExpression<?>> selections = new ArrayList<>(projection.columns().size());
    for (QueryColumn<E, ?> column : projection.columns()) {
      selections.add(column.expression());
    }
    return compile(model, table, selections, equalityProperty, projection.rowDecoder(model));
  }

  private <E, R> CompiledQueryPlan<R, Object> compile(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      List<? extends SqlExpression<?>> selections,
      @Nullable PropertyMeta<E, ?> equalityProperty,
      RowDecoder<R> rowDecoder) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(selections, "selections");
    Objects.requireNonNull(rowDecoder, "rowDecoder");
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
        dialect.renderer().render(new SelectStatement(selections, table, predicate));
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
        rowDecoder);
  }

  private static <E, V> SqlPredicate equalityPredicate(
      QueryTable<E> table, PropertyMeta<E, V> property) {
    ColumnExpression<E, V> column = table.expression(property);
    return column.eq(new ParameterSlot<>(0, property.javaType(), false));
  }
}
