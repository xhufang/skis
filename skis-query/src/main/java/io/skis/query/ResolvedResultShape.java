package io.skis.query;

import io.skis.mapping.RowDecoder;
import io.skis.mapping.RowLayout;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Query-compiler-local result shape with resolved value mappings and JDBC indexes. */
record ResolvedResultShape<R>(
    List<SqlExpression<?>> expressions, RowDecoder<R> decoder, List<ResolvedSelection> selections) {

  ResolvedResultShape {
    expressions = List.copyOf(expressions);
    selections = List.copyOf(selections);
    if (expressions.isEmpty()) {
      throw new QueryValidationException("a result shape must contain at least one expression");
    }
    Objects.requireNonNull(decoder, "decoder");
    if (expressions.size() != selections.size()) {
      throw new QueryValidationException(
          "resolved result selection count does not match its SQL expression count");
    }
    for (int ordinal = 0; ordinal < selections.size(); ordinal++) {
      ResolvedSelection selection = selections.get(ordinal);
      if (selection.resultIndex() != ordinal + 1) {
        throw new QueryValidationException("resolved result indexes must be dense and one-based");
      }
      if (!expressions.get(ordinal).equals(selection.valueMapping().selectable().expression())) {
        throw new QueryValidationException(
            "resolved value mapping does not match its selected SQL expression");
      }
    }
  }

  static <E> ResolvedResultShape<E> entity(
      QueryTable<E> table, TableRuntimeScope scope, boolean nullableResult) {
    TableRuntimeScope.Occurrence<E> occurrence = scope.require(table);
    var model = occurrence.model();
    if (nullableResult && model.entity().primaryKey().isEmpty()) {
      throw new QueryValidationException(
          "selectNullable(table) requires complete non-null primary-key metadata for "
              + occurrence.description());
    }
    if (!nullableResult && scope.isNullExtended(table)) {
      throw new QueryValidationException(
          "non-null entity selection references null-extended "
              + occurrence.description()
              + "; use selectNullable(table)");
    }
    List<ResolvedSelection> resolved = new ArrayList<>(model.entity().properties().size());
    for (PropertyMeta<E, ?> property : model.entity().properties()) {
      QueryColumn<E, ?> column = table.queryColumn(property);
      resolved.add(
          new ResolvedSelection(
              ResolvedValueMapping.resolve(column, scope), property.ordinal() + 1));
    }
    RowLayout layout = RowLayout.contiguous(model.properties().size(), 1);
    RowDecoder<E> decoder =
        nullableResult ? model.nullableRowDecoder(layout) : model.fullRowDecoder();
    return new ResolvedResultShape<>(List.copyOf(table.selections()), decoder, resolved);
  }

  static <R> ResolvedResultShape<R> scalar(
      Selectable<R> selectable, TableRuntimeScope scope, boolean nullableResult) {
    ResolvedValueMapping<R> mapping = ResolvedValueMapping.resolve(selectable, scope);
    if (!nullableResult && mapping.effectiveNullability().isNullable()) {
      throw new QueryValidationException(
          "non-null scalar selection '"
              + SelectableSupport.summary(selectable)
              + "' is effectively nullable; use selectNullable(selectable)");
    }
    ProjectionMapping.ValueReader<R> reader = mapping.reader(1, !nullableResult);
    RowDecoder<R> decoder =
        (resultSet, context) -> {
          R value = reader.read(resultSet, context);
          if (value == null && !nullableResult) {
            throw new SQLException("required scalar result is null at JDBC index 1");
          }
          return value;
        };
    return new ResolvedResultShape<>(
        List.of(selectable.expression()), decoder, List.of(new ResolvedSelection(mapping, 1)));
  }

  static <R> ResolvedResultShape<R> projection(
      ProjectionSelection<R> selection, TableRuntimeScope scope) {
    ProjectionMapping<R> mapping = selection.mapping();
    List<ProjectionMapping.Parameter> parameters = mapping.parameters();
    List<Selectable<?>> selectables = selection.selections();
    if (parameters.size() != selectables.size()) {
      throw new QueryValidationException(
          "projection '"
              + mapping.resultType().getTypeName()
              + "' requires "
              + parameters.size()
              + " selections but received "
              + selectables.size());
    }
    List<SqlExpression<?>> expressions = new ArrayList<>(selectables.size());
    List<ResolvedSelection> resolved = new ArrayList<>(selectables.size());
    for (int ordinal = 0; ordinal < selectables.size(); ordinal++) {
      Selectable<?> selectable = selectables.get(ordinal);
      ProjectionMapping.Parameter parameter = parameters.get(ordinal);
      ResolvedValueMapping<?> valueMapping;
      String location = resolutionLocation(selectable, scope);
      try {
        valueMapping = ResolvedValueMapping.resolve(selectable, scope);
      } catch (QueryValidationException failure) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            location,
            selectable.nullability(),
            "cannot be resolved in the final query scope: " + failure.getMessage());
      }
      if (!parameter.javaType().equals(valueMapping.javaType())) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            location,
            valueMapping.effectiveNullability(),
            "has an incompatible boxed Java type");
      }
      SqlType expectedSqlType = SqlType.fromJavaType(parameter.javaType());
      if (expectedSqlType == SqlType.OTHER
          || valueMapping.sqlType() == SqlType.OTHER
          || !expectedSqlType.equalityCompatibleWith(valueMapping.sqlType())) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            location,
            valueMapping.effectiveNullability(),
            "has an incompatible SQL type");
      }
      if (parameter.acceptsNoNull() && valueMapping.effectiveNullability().isNullable()) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            location,
            valueMapping.effectiveNullability(),
            "cannot satisfy the constructor's non-null contract");
      }
      expressions.add(selectable.expression());
      resolved.add(new ResolvedSelection(valueMapping, ordinal + 1));
    }
    ProjectionMapping.Readers readers = new CompiledReaders(parameters, resolved);
    RowDecoder<R> generated =
        Objects.requireNonNull(mapping.decoderFactory().create(readers), "projection row decoder");
    RowDecoder<R> decoder =
        (resultSet, context) -> {
          R value = generated.decode(resultSet, context);
          if (value == null) {
            throw new SQLException(
                "generated projection decoder returned null for '"
                    + mapping.resultType().getTypeName()
                    + "'");
          }
          return value;
        };
    return new ResolvedResultShape<>(expressions, decoder, resolved);
  }

  private static String resolutionLocation(Selectable<?> selectable, TableRuntimeScope scope) {
    if (selectable instanceof QueryColumn<?, ?> column) {
      try {
        return scope.require(column.table()).description();
      } catch (QueryValidationException ignored) {
        return "an unresolved table occurrence";
      }
    }
    return "a query-local expression";
  }

  private static QueryValidationException projectionFailure(
      ProjectionMapping<?> mapping,
      ProjectionMapping.Parameter parameter,
      Selectable<?> selectable,
      String location,
      Nullability effectiveNullability,
      String reason) {
    SqlType expectedSqlType = SqlType.fromJavaType(parameter.javaType());
    return new QueryValidationException(
        "projection '"
            + mapping.resultType().getTypeName()
            + "' parameter #"
            + (parameter.ordinal() + 1)
            + " '"
            + parameter.name()
            + "' expects "
            + parameter.nullability()
            + ' '
            + parameter.javaType().getTypeName()
            + " / SQL "
            + expectedSqlType
            + ", but selection '"
            + SelectableSupport.summary(selectable)
            + "' at "
            + location
            + " has "
            + selectable.javaType().getTypeName()
            + " / SQL "
            + selectable.sqlType()
            + " / effective "
            + effectiveNullability
            + " and "
            + reason);
  }

  private record CompiledReaders(
      List<ProjectionMapping.Parameter> parameters, List<ResolvedSelection> selections)
      implements ProjectionMapping.Readers {

    private CompiledReaders {
      parameters = List.copyOf(parameters);
      selections = List.copyOf(selections);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> ProjectionMapping.ValueReader<V> reader(int parameterOrdinal, Class<?> javaType) {
      Objects.requireNonNull(javaType, "javaType");
      if (parameterOrdinal < 0 || parameterOrdinal >= parameters.size()) {
        throw new QueryValidationException(
            "projection reader parameter ordinal "
                + parameterOrdinal
                + " is outside [0, "
                + parameters.size()
                + ")");
      }
      ProjectionMapping.Parameter parameter = parameters.get(parameterOrdinal);
      if (!parameter.javaType().equals(javaType)) {
        throw new QueryValidationException(
            "generated projection reader for parameter '"
                + parameter.name()
                + "' requested "
                + javaType.getTypeName()
                + " but its mapping contract requires "
                + parameter.javaType().getTypeName());
      }
      return (ProjectionMapping.ValueReader<V>)
          selections.get(parameterOrdinal).reader(parameter.acceptsNoNull());
    }
  }

  /** One result selection after value mapping and one-based JDBC layout resolution. */
  record ResolvedSelection(ResolvedValueMapping<?> valueMapping, int resultIndex) {

    ResolvedSelection {
      Objects.requireNonNull(valueMapping, "valueMapping");
      if (resultIndex < 1) {
        throw new IllegalArgumentException("result index must be positive");
      }
    }

    ProjectionMapping.ValueReader<?> reader(boolean requireNonNull) {
      return valueMapping.reader(resultIndex, requireNonNull);
    }
  }
}
