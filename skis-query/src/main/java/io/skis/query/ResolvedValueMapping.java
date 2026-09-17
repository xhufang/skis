package io.skis.query;

import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcTypeCodec;
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowReadContext;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query-local value contract resolved from a framework-owned selectable expression. */
record ResolvedValueMapping<V>(
    Selectable<V> selectable,
    SqlExpression<V> expression,
    Class<V> javaType,
    SqlType sqlType,
    Nullability effectiveNullability,
    JdbcTypeCodec<V> codec,
    int sourceOccurrenceOrdinal) {

  ResolvedValueMapping {
    Objects.requireNonNull(selectable, "selectable");
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    Objects.requireNonNull(effectiveNullability, "effectiveNullability");
    Objects.requireNonNull(codec, "codec");
    if (sourceOccurrenceOrdinal < -1) {
      throw new IllegalArgumentException("source occurrence ordinal must be -1 or non-negative");
    }
    if (!javaType.equals(selectable.javaType()) || !javaType.equals(expression.javaType())) {
      throw new QueryValidationException(
          "selectable Java type does not match its resolved value mapping");
    }
    if (sqlType != selectable.sqlType() || sqlType != expression.sqlType()) {
      throw new QueryValidationException(
          "selectable SQL type does not match its resolved value mapping");
    }
    if (selectable.nullability() != expression.nullability()) {
      throw new QueryValidationException(
          "selectable nullability does not match its SQL expression");
    }
  }

  static <V> ResolvedValueMapping<V> resolve(Selectable<V> selectable, TableRuntimeScope scope) {
    return resolve(selectable, selectable.expression(), scope);
  }

  static <V> ResolvedValueMapping<V> resolve(
      Selectable<V> selectable, SqlExpression<V> expression, TableRuntimeScope scope) {
    Objects.requireNonNull(selectable, "selectable");
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(scope, "scope");
    return switch (selectable) {
      case QueryColumn<?, ?> column -> resolveColumnUntyped(column, expression, scope);
      case DerivedColumnSelectable<?> derived -> resolveDerivedUntyped(derived, expression, scope);
      case ScalarSubquerySelectable<?> scalar -> resolveScalarUntyped(scalar, expression, scope);
      default ->
          throw new QueryValidationException(
              "no query-local value mapping is registered for framework expression '"
                  + SelectableSupport.summary(selectable)
                  + "'");
    };
  }

  ProjectionMapping.ValueReader<V> reader(int resultIndex, boolean requireNonNull) {
    if (resultIndex < 1) {
      throw new IllegalArgumentException("result index must be positive");
    }
    return (resultSet, context) -> {
      V value = read(resultSet, resultIndex, context);
      if (value == null && requireNonNull) {
        throw new SQLException(
            "required selection '"
                + SelectableSupport.summary(selectable)
                + "' is null at JDBC index "
                + resultIndex);
      }
      return value;
    };
  }

  @Nullable V read(ResultSet resultSet, int resultIndex, RowReadContext context)
      throws SQLException {
    Objects.requireNonNull(resultSet, "resultSet");
    Objects.requireNonNull(context, "context");
    if (resultIndex < 1) {
      throw new IllegalArgumentException("result index must be positive");
    }
    return codec.read(resultSet, resultIndex, context);
  }

  void bind(
      PreparedStatement statement,
      int parameterIndex,
      @Nullable Object value,
      JdbcWriteContext context)
      throws SQLException {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(context, "context");
    if (parameterIndex < 1) {
      throw new IllegalArgumentException("JDBC parameter index must be positive");
    }
    if (value != null && !javaType.isInstance(value)) {
      throw new SQLException(
          "query value for expression '"
              + SelectableSupport.summary(selectable)
              + "' requires "
              + javaType.getTypeName()
              + " but received "
              + value.getClass().getTypeName());
    }
    codec.bind(statement, parameterIndex, value == null ? null : javaType.cast(value), context);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <V> ResolvedValueMapping<V> resolveColumnUntyped(
      QueryColumn<?, ?> column, SqlExpression<V> expression, TableRuntimeScope scope) {
    return (ResolvedValueMapping<V>)
        resolveColumn((QueryColumn) column, (SqlExpression) expression, scope);
  }

  private static <E, V> ResolvedValueMapping<V> resolveColumn(
      QueryColumn<E, V> column, SqlExpression<V> expression, TableRuntimeScope scope) {
    TableRuntimeScope.Occurrence<E> occurrence = scope.require(column.table());
    PropertyRuntime<E, V> runtime = scope.property(column);
    return new ResolvedValueMapping<>(
        column,
        expression,
        column.javaType(),
        column.sqlType(),
        scope.effectiveNullability(column),
        runtime.codec(),
        occurrence.occurrenceOrdinal());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <V> ResolvedValueMapping<V> resolveDerivedUntyped(
      DerivedColumnSelectable<?> derived, SqlExpression<V> expression, TableRuntimeScope scope) {
    DerivedColumnSelectable<V> typed = (DerivedColumnSelectable) derived;
    if (!(expression instanceof io.skis.sql.ast.DerivedColumnExpression<?> compiled)
        || compiled.relation() != typed.relation().reference()
        || compiled.outputOrdinal() != typed.outputOrdinal()) {
      throw new QueryValidationException(
          "compiled derived selection does not reference its concrete relation occurrence and "
              + "output ordinal");
    }
    JdbcTypeCodec<V> codec =
        (JdbcTypeCodec<V>) outputCodec(typed.output().selectable(), scope.registry());
    return new ResolvedValueMapping<>(
        typed,
        expression,
        typed.javaType(),
        typed.sqlType(),
        scope.effectiveNullability(typed),
        codec,
        scope.require(typed.relation()));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <V> ResolvedValueMapping<V> resolveScalarUntyped(
      ScalarSubquerySelectable<?> scalar, SqlExpression<V> expression, TableRuntimeScope scope) {
    if (!(expression instanceof ScalarSubqueryExpression<?>)) {
      throw new QueryValidationException(
          "compiled scalar selection is not backed by a scalar-subquery AST node");
    }
    ScalarSubquerySelectable<V> typed = (ScalarSubquerySelectable) scalar;
    JdbcTypeCodec<V> codec = (JdbcTypeCodec<V>) outputCodec(typed.output(), scope.registry());
    return new ResolvedValueMapping<>(
        typed, expression, typed.javaType(), typed.sqlType(), Nullability.NULLABLE, codec, -1);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static JdbcTypeCodec<?> outputCodec(
      Selectable<?> output, EntityRuntimeRegistry registry) {
    switch (output) {
      case QueryColumn<?, ?> column -> {
        EntityRuntimeModel model = registry.require(column.table().entity());
        return model.property(column.property()).codec();
      }
      case DerivedColumnSelectable<?> derived -> {
        return outputCodec(derived.output().selectable(), registry);
      }
      case ScalarSubquerySelectable<?> scalar -> {
        return outputCodec(scalar.output(), registry);
      }
      default -> {}
    }
    throw new QueryValidationException(
        "no result Codec is registered for selectable output '"
            + SelectableSupport.summary(output)
            + "'");
  }

  static JdbcTypeCodec<?> codecFor(Selectable<?> selectable, EntityRuntimeRegistry registry) {
    return outputCodec(
        Objects.requireNonNull(selectable, "selectable"),
        Objects.requireNonNull(registry, "registry"));
  }
}
