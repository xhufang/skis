package io.skis.query;

import io.skis.mapping.JdbcTypeCodec;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowDecoder;
import io.skis.mapping.RowLayout;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query-compiler-local result shape with resolved occurrences, codecs, and JDBC indexes. */
record ResolvedResultShape<R>(
    List<SqlExpression<?>> expressions,
    RowDecoder<R> decoder,
    List<ResolvedSelectable> selections) {

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
  }

  static <E> ResolvedResultShape<E> entity(
      QueryTable<E> table,
      EntityPlanSet<E> plans,
      TableRuntimeScope scope,
      boolean nullableResult) {
    TableRuntimeScope.Occurrence<E> occurrence = scope.require(table);
    if (occurrence.model() != plans.model()) {
      throw new QueryValidationException(
          occurrence.description() + " does not use the selected target's canonical runtime model");
    }
    if (nullableResult && plans.entity().primaryKey().isEmpty()) {
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
    List<ResolvedSelectable> resolved = new ArrayList<>(plans.entity().properties().size());
    for (PropertyMeta<E, ?> property : plans.entity().properties()) {
      QueryColumn<E, ?> column = table.queryColumn(property);
      resolved.add(resolveColumn(column, scope, property.ordinal() + 1));
    }
    RowLayout layout = RowLayout.contiguous(plans.model().properties().size(), 1);
    RowDecoder<E> decoder =
        nullableResult ? plans.model().nullableRowDecoder(layout) : plans.model().fullRowDecoder();
    List<SqlExpression<?>> expressions = new ArrayList<>(table.selections());
    return new ResolvedResultShape<>(expressions, decoder, resolved);
  }

  static <E, R> ResolvedResultShape<R> scalar(
      QueryTable<E> table,
      EntityPlanSet<E> plans,
      QueryColumn<E, R> column,
      TableRuntimeScope scope,
      boolean nullableResult) {
    TableRuntimeScope.Occurrence<E> occurrence = scope.require(table);
    if (occurrence.model() != plans.model()) {
      throw new QueryValidationException(
          occurrence.description() + " does not use the selected target's canonical runtime model");
    }
    ResolvedSelectable selected = resolveColumn(column, scope, 1);
    if (!nullableResult && selected.effectiveNullability().isNullable()) {
      throw new QueryValidationException(
          "non-null scalar selection '"
              + selectionSummary(column)
              + "' references effectively nullable "
              + occurrence.description()
              + "; use selectNullable(column)");
    }
    ProjectionMapping.ValueReader<R> reader = selected.reader(!nullableResult);
    RowDecoder<R> decoder =
        (resultSet, context) -> {
          R value = reader.read(resultSet, context);
          if (value == null && !nullableResult) {
            throw new SQLException("required scalar result is null at JDBC index 1");
          }
          return value;
        };
    return new ResolvedResultShape<>(List.of(column.expression()), decoder, List.of(selected));
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
    List<ResolvedSelectable> resolved = new ArrayList<>(selectables.size());
    for (int ordinal = 0; ordinal < selectables.size(); ordinal++) {
      Selectable<?> selectable = selectables.get(ordinal);
      ProjectionMapping.Parameter parameter = parameters.get(ordinal);
      if (!(selectable instanceof QueryColumn<?, ?> column)) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            null,
            selectable.nullability(),
            "is not a supported 0.2.4 column selection");
      }
      TableRuntimeScope.Occurrence<?> occurrence;
      Nullability effectiveNullability;
      try {
        occurrence = scope.require(column.table());
        effectiveNullability = scope.effectiveNullability(column);
      } catch (QueryValidationException failure) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            null,
            selectable.nullability(),
            "cannot be resolved in the final query scope: " + failure.getMessage());
      }
      if (!parameter.javaType().equals(selectable.javaType())) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            occurrence,
            effectiveNullability,
            "has an incompatible boxed Java type");
      }
      SqlType expectedSqlType = SqlType.fromJavaType(parameter.javaType());
      if (expectedSqlType == SqlType.OTHER
          || selectable.sqlType() == SqlType.OTHER
          || !expectedSqlType.equalityCompatibleWith(selectable.sqlType())) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            occurrence,
            effectiveNullability,
            "has an incompatible SQL type");
      }
      if (parameter.acceptsNoNull() && effectiveNullability.isNullable()) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            occurrence,
            effectiveNullability,
            "cannot satisfy the constructor's non-null contract");
      }
      expressions.add(selectable.expression());
      try {
        resolved.add(resolveColumn(column, scope, ordinal + 1));
      } catch (QueryValidationException failure) {
        throw projectionFailure(
            mapping,
            parameter,
            selectable,
            occurrence,
            effectiveNullability,
            "has no canonical JDBC codec: " + failure.getMessage());
      }
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

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ResolvedSelectable resolveColumn(
      QueryColumn<?, ?> column, TableRuntimeScope scope, int resultIndex) {
    TableRuntimeScope.Occurrence<?> occurrence = scope.require(column.table());
    PropertyRuntime<?, ?> runtime = scope.property((QueryColumn) column);
    return new ResolvedSelectable(
        column,
        occurrence.occurrenceOrdinal(),
        scope.effectiveNullability(column),
        runtime,
        resultIndex);
  }

  private static QueryValidationException projectionFailure(
      ProjectionMapping<?> mapping,
      ProjectionMapping.Parameter parameter,
      Selectable<?> selectable,
      TableRuntimeScope.@Nullable Occurrence<?> occurrence,
      Nullability effectiveNullability,
      String reason) {
    String occurrenceDescription =
        occurrence == null ? "an unresolved table occurrence" : occurrence.description();
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
            + selectionSummary(selectable)
            + "' at "
            + occurrenceDescription
            + " has "
            + selectable.javaType().getTypeName()
            + " / SQL "
            + selectable.sqlType()
            + " / effective "
            + effectiveNullability
            + " and "
            + reason);
  }

  private static String selectionSummary(Selectable<?> selectable) {
    if (!(selectable instanceof QueryColumn<?, ?> column)) {
      return selectable.expression().getClass().getSimpleName();
    }
    String qualifier =
        column
            .table()
            .alias()
            .map(Identifier::value)
            .orElse(column.table().entity().table().name());
    return qualifier + '.' + column.property().name();
  }

  private record CompiledReaders(
      List<ProjectionMapping.Parameter> parameters, List<ResolvedSelectable> selections)
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

  /** One selection after query-local occurrence, nullability, codec, and layout resolution. */
  record ResolvedSelectable(
      QueryColumn<?, ?> column,
      int occurrenceOrdinal,
      Nullability effectiveNullability,
      PropertyRuntime<?, ?> runtime,
      int resultIndex) {

    ResolvedSelectable {
      Objects.requireNonNull(column, "column");
      if (occurrenceOrdinal < 0) {
        throw new IllegalArgumentException("occurrence ordinal must not be negative");
      }
      Objects.requireNonNull(effectiveNullability, "effectiveNullability");
      Objects.requireNonNull(runtime, "runtime");
      if (resultIndex < 1) {
        throw new IllegalArgumentException("result index must be positive");
      }
    }

    @SuppressWarnings("unchecked")
    <V> ProjectionMapping.ValueReader<V> reader(boolean requireNonNull) {
      PropertyRuntime<?, V> typed = (PropertyRuntime<?, V>) runtime;
      JdbcTypeCodec<V> codec = typed.codec();
      return (resultSet, context) -> {
        V value = read(codec, resultSet, resultIndex, context);
        if (value == null && requireNonNull) {
          throw new SQLException(
              "required projection selection '"
                  + selectionSummary(column)
                  + "' is null at JDBC index "
                  + resultIndex);
        }
        return value;
      };
    }
  }

  private static <V> @Nullable V read(
      JdbcTypeCodec<V> codec, ResultSet resultSet, int index, RowReadContext context)
      throws SQLException {
    return codec.read(resultSet, index, context);
  }
}
