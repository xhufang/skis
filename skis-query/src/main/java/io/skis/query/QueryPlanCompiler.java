package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowDecoder;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Compiles immutable single-table predicate shapes into value-independent JDBC plans. */
final class QueryPlanCompiler {

  private final Dialect dialect;

  QueryPlanCompiler(Dialect dialect) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
  }

  /** Compiles the no-predicate or one-property equality Fast Path. */
  <E> CompiledQueryPlan<E, Object> compile(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable PropertyMeta<E, ?> equalityProperty) {
    PredicateShape<E> shape = equalityShape(table, equalityProperty);
    return compile(model, table, table.selections(), shape, model.fullRowDecoder());
  }

  <E> CompiledQueryPlan<E, Object> compileQuery(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable QueryPredicate<E> predicate) {
    return compile(
        model, table, table.selections(), predicateShape(predicate), model.fullRowDecoder());
  }

  <E, R> CompiledQueryPlan<R, Object> compileProjection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate<E> predicate) {
    Objects.requireNonNull(projection, "projection").validateFrom(table);
    List<QueryColumn<E, ?>> columns = projection.columns(table);
    List<SqlExpression<?>> selections = new ArrayList<>(columns.size());
    for (QueryColumn<E, ?> column : columns) {
      selections.add(column.expression());
    }
    return compile(
        model,
        table,
        selections,
        predicateShape(predicate),
        projection.rowDecoder(model));
  }

  private <E, R> CompiledQueryPlan<R, Object> compile(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      List<? extends SqlExpression<?>> selections,
      PredicateShape<E> shape,
      RowDecoder<R> rowDecoder) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(selections, "selections");
    Objects.requireNonNull(shape, "shape");
    Objects.requireNonNull(rowDecoder, "rowDecoder");
    if (table.entity() != model.entity()) {
      throw new QueryValidationException(
          "query table does not use the canonical runtime metadata of entity '"
              + model.entity().entityName()
              + "'");
    }

    List<PropertyRuntime<E, ?>> parameterRuntimes = new ArrayList<>(shape.properties().size());
    try {
      for (PropertyMeta<E, ?> property : shape.properties()) {
        parameterRuntimes.add(model.property(property));
      }
    } catch (IllegalArgumentException exception) {
      throw new QueryValidationException(
          "predicate property does not belong to entity '"
              + model.entity().entityName()
              + "'",
          exception);
    }

    SelectStatement statement;
    try {
      statement = new SelectStatement(selections, table, shape.ast());
    } catch (IllegalArgumentException exception) {
      throw new QueryValidationException(
          "invalid single-table predicate: " + exception.getMessage(), exception);
    }
    RenderedSql rendered = dialect.renderer().render(statement);
    List<RenderedBinding<E>> renderedBindings =
        renderedBindings(model, shape.properties(), parameterRuntimes, rendered);

    int logicalParameterCount = parameterRuntimes.size();
    return new CompiledQueryPlan<>(
        dialect.id(),
        rendered,
        (preparedStatement, firstIndex, argument, context) -> {
          List<?> values = requireArguments(argument, logicalParameterCount);
          int index = firstIndex;
          for (RenderedBinding<E> binding : renderedBindings) {
            binding
                .runtime()
                .bind(
                    preparedStatement,
                    index,
                    values.get(binding.argumentOrdinal()),
                    context);
            index++;
          }
          return index;
        },
        rowDecoder);
  }

  private <E> List<RenderedBinding<E>> renderedBindings(
      EntityRuntimeModel<E> model,
      List<PropertyMeta<E, ?>> properties,
      List<PropertyRuntime<E, ?>> runtimes,
      RenderedSql rendered) {
    if (rendered.parameterCount() != runtimes.size()) {
      throw unexpectedParameterShape(model);
    }

    boolean[] seenOrdinals = new boolean[runtimes.size()];
    List<RenderedBinding<E>> bindings = new ArrayList<>(runtimes.size());
    for (ParameterSlot<?> renderedSlot : rendered.parameters()) {
      int ordinal = renderedSlot.ordinal();
      if (ordinal < 0 || ordinal >= runtimes.size() || seenOrdinals[ordinal]) {
        throw unexpectedParameterShape(model);
      }
      ParameterSlot<?> expectedSlot = expectedSlot(ordinal, properties.get(ordinal));
      if (!renderedSlot.javaType().equals(expectedSlot.javaType())
          || renderedSlot.sqlType() != expectedSlot.sqlType()
          || renderedSlot.nullability() != expectedSlot.nullability()) {
        throw unexpectedParameterShape(model);
      }
      seenOrdinals[ordinal] = true;
      bindings.add(new RenderedBinding<>(runtimes.get(ordinal), ordinal));
    }
    return List.copyOf(bindings);
  }

  private QueryValidationException unexpectedParameterShape(EntityRuntimeModel<?> model) {
    return new QueryValidationException(
        "dialect '"
            + dialect.id()
            + "' rendered an unexpected parameter shape for entity '"
            + model.entity().entityName()
            + "'");
  }

  private static <E, V> ParameterSlot<V> expectedSlot(
      int ordinal, PropertyMeta<E, V> property) {
    return new ParameterSlot<>(
        ordinal,
        property.javaType(),
        SqlType.fromJavaType(property.javaType()),
        Nullability.NON_NULL);
  }

  private static List<?> requireArguments(Object argument, int expectedCount)
      throws SQLException {
    Objects.requireNonNull(argument, "argument");
    if (expectedCount == 0) {
      if (argument != NoParameters.INSTANCE) {
        throw new SQLException("compiled query does not accept parameters");
      }
      return List.of();
    }
    if (argument instanceof QueryArguments(List<Object> values)) {
      if (values.size() != expectedCount) {
        throw new SQLException(
            "compiled query requires "
                + expectedCount
                + " logical parameters but received "
                + values.size());
      }
      return values;
    }
    if (expectedCount == 1 && argument != NoParameters.INSTANCE) {
      return List.of(argument);
    }
    throw new SQLException("compiled query requires " + expectedCount + " logical parameters");
  }

  private static <E> PredicateShape<E> predicateShape(
      @Nullable QueryPredicate<E> predicate) {
    if (predicate == null) {
      return new PredicateShape<>(null, List.of());
    }
    CompiledQueryPredicate<E> compiled = predicate.compile();
    return new PredicateShape<>(compiled.ast(), compiled.properties());
  }

  private static <E> PredicateShape<E> equalityShape(
      QueryTable<E> table, @Nullable PropertyMeta<E, ?> property) {
    if (property == null) {
      return new PredicateShape<>(null, List.of());
    }
    return equalityShapeTyped(table, property);
  }

  private static <E, V> PredicateShape<E> equalityShapeTyped(
      QueryTable<E> table, PropertyMeta<E, V> property) {
    ColumnExpression<E, V> column = table.expression(property);
    ParameterSlot<V> slot =
        new ParameterSlot<>(
            0, property.javaType(), column.sqlType(), Nullability.NON_NULL);
    return new PredicateShape<>(column.eq(slot), List.of(property));
  }

  private record PredicateShape<E>(
      @Nullable SqlPredicate ast, List<PropertyMeta<E, ?>> properties) {

    private PredicateShape {
      properties = List.copyOf(properties);
    }
  }

  private record RenderedBinding<E>(
      PropertyRuntime<E, ?> runtime, int argumentOrdinal) {}
}
