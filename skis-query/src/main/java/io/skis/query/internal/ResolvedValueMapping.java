package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.query.Selectable;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.IdentityHashMap;
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
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_RESULT_MAPPING,
          "selectable Java type does not match its resolved value mapping");
    }
    if (sqlType != selectable.sqlType() || sqlType != expression.sqlType()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_RESULT_MAPPING,
          "selectable SQL type does not match its resolved value mapping");
    }
    if (selectable.nullability() != expression.nullability()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_RESULT_MAPPING,
          "selectable nullability does not match its SQL expression");
    }
  }

  static <V> ResolvedValueMapping<V> resolve(Selectable<V> selectable, TableRuntimeScope scope) {
    return resolve(selectable, SelectableSupport.expression(selectable), scope);
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
      case ExpressionSelectable<?> ignored -> resolveExpression(selectable, expression, scope);
      default ->
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_RESULT_MAPPING,
              "no query-local value mapping is registered for framework expression '"
                  + SelectableSupport.summary(selectable)
                  + "'");
    };
  }

  @SuppressWarnings("unchecked")
  private static <V> ResolvedValueMapping<V> resolveExpression(
      Selectable<V> selectable, SqlExpression<V> expression, TableRuntimeScope scope) {
    JdbcTypeCodec<V> codec = (JdbcTypeCodec<V>) scope.outputCodec(selectable);
    return new ResolvedValueMapping<>(
        selectable,
        expression,
        selectable.javaType(),
        selectable.sqlType(),
        scope.effectiveNullability(expression),
        codec,
        -1);
  }

  ProjectionMapping.ValueReader<@Nullable V> reader(int resultIndex, boolean requireNonNull) {
    if (resultIndex < 1) {
      throw new IllegalArgumentException("result index must be positive");
    }
    JdbcTypeCodec<V> readerCodec = codec;
    // The compiled AST is authoritative; asking a standard selectable for its summary would
    // materialize an independent preview just to describe a possible read failure.
    String selectionSummary =
        selectable instanceof QueryColumn<?, ?> || selectable instanceof DerivedColumnSelectable<?>
            ? SelectableSupport.summary(selectable)
            : expression.getClass().getSimpleName();
    return (resultSet, context) -> {
      Objects.requireNonNull(resultSet, "resultSet");
      Objects.requireNonNull(context, "context");
      V value = readerCodec.read(resultSet, resultIndex, context);
      if (value == null && requireNonNull) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_NON_NULL_RESULT,
            "required selection '" + selectionSummary + "' is null at JDBC index " + resultIndex);
      }
      return value;
    };
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
    if (!(expression
            instanceof
            DerivedColumnExpression<?>(DerivedRelationReference relation, int outputOrdinal))
        || relation != QueryModels.relation(typed.relation()).reference()
        || outputOrdinal != typed.outputOrdinal()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_RESULT_MAPPING,
          "compiled derived selection does not reference its concrete relation occurrence and "
              + "output ordinal");
    }
    JdbcTypeCodec<V> codec =
        (JdbcTypeCodec<V>) scope.outputCodec(QueryModels.selectable(typed.output()));
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
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_RESULT_MAPPING,
          "compiled scalar selection is not backed by a scalar-subquery AST node");
    }
    ScalarSubquerySelectable<V> typed = (ScalarSubquerySelectable) scalar;
    JdbcTypeCodec<V> codec = (JdbcTypeCodec<V>) scope.outputCodec(typed.output());
    return new ResolvedValueMapping<>(
        typed, expression, typed.javaType(), typed.sqlType(), Nullability.NULLABLE, codec, -1);
  }

  private static JdbcTypeCodec<?> outputCodec(
      Selectable<?> output, EntityRuntimeRegistry registry) {
    if (output instanceof QueryColumn<?, ?> column) {
      return propertyCodec(column, registry);
    }
    return outputCodec(output, registry, new IdentityHashMap<>());
  }

  private static <E, V> JdbcTypeCodec<V> propertyCodec(
      QueryColumn<E, V> column, EntityRuntimeRegistry registry) {
    return registry.require(column.table().entity()).property(column.property()).codec();
  }

  static JdbcTypeCodec<?> outputCodec(
      Selectable<?> output,
      EntityRuntimeRegistry registry,
      IdentityHashMap<Object, JdbcTypeCodec<?>> resolved) {
    Object identity =
        output instanceof ExpressionSelectable<?> expression ? expression.codecIdentity() : output;
    JdbcTypeCodec<?> existing = resolved.get(identity);
    if (existing != null) {
      return existing;
    }
    JdbcTypeCodec<?> codec = resolveOutputCodec(output, registry, resolved);
    resolved.put(identity, codec);
    return codec;
  }

  private static JdbcTypeCodec<?> resolveOutputCodec(
      Selectable<?> output,
      EntityRuntimeRegistry registry,
      IdentityHashMap<Object, JdbcTypeCodec<?>> resolved) {
    switch (output) {
      case QueryColumn<?, ?> column -> {
        return propertyCodec(column, registry);
      }
      case DerivedColumnSelectable<?> derived -> {
        return outputCodec(QueryModels.selectable(derived.output()), registry, resolved);
      }
      case ScalarSubquerySelectable<?> scalar -> {
        return outputCodec(scalar.output(), registry, resolved);
      }
      case ExpressionSelectable<?> expression -> {
        for (Selectable<?> source : expression.valueSources()) {
          requireBuiltin(source, outputCodec(source, registry, resolved));
        }
        if (expression.decomposed()) {
          QueryInputMappings.validateValue(expression.expression(), registry);
        }
        return requireBuiltinCodec(expression.javaType(), expression.sqlType());
      }
      default -> {}
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_RESULT_MAPPING,
        "no result Codec is registered for selectable output '"
            + SelectableSupport.summary(output)
            + "'");
  }

  private static void requireBuiltin(Selectable<?> source, JdbcTypeCodec<?> actual) {
    if (actual != requireBuiltinCodec(source.javaType(), source.sqlType())) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          "standard expression input requires the exact built-in Codec for '"
              + sourceSummary(source)
              + "'");
    }
  }

  private static String sourceSummary(Selectable<?> source) {
    return switch (source) {
      case ScalarSubquerySelectable<?> scalar -> "scalar output " + sourceSummary(scalar.output());
      case DerivedColumnSelectable<?> derived ->
          SelectableSupport.summary(derived)
              + " from "
              + sourceSummary(QueryModels.selectable(derived.output()));
      default -> SelectableSupport.summary(source);
    };
  }

  static JdbcTypeCodec<?> requireBuiltinCodec(Class<?> javaType, SqlType sqlType) {
    JdbcTypeCodec<?> codec =
        SqlType.fromJavaType(javaType) == sqlType ? builtinCodec(javaType) : null;
    if (codec == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          "no exact built-in Codec for " + javaType.getTypeName() + " / SQL " + sqlType);
    }
    return codec;
  }

  static JdbcTypeCodec<?> codecFor(Selectable<?> selectable, EntityRuntimeRegistry registry) {
    return outputCodec(
        Objects.requireNonNull(selectable, "selectable"),
        Objects.requireNonNull(registry, "registry"));
  }

  static void requireBuiltinParameterSource(Selectable<?> source, JdbcTypeCodec<?> actual) {
    JdbcTypeCodec<?> expected = builtinCodec(source.javaType());
    if (expected == null || actual != expected) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_LAYOUT,
          "a parameter shared with a standard expression requires the exact built-in Codec for '"
              + SelectableSupport.summary(source)
              + "'");
    }
  }

  private static @Nullable JdbcTypeCodec<?> builtinCodec(Class<?> javaType) {
    return switch (SqlType.fromJavaType(javaType)) {
      case BOOLEAN -> JdbcCodecs.BOOLEAN;
      case TINYINT -> JdbcCodecs.BYTE;
      case SMALLINT -> JdbcCodecs.SHORT;
      case INTEGER -> JdbcCodecs.INTEGER;
      case BIGINT -> JdbcCodecs.LONG;
      case REAL -> JdbcCodecs.FLOAT;
      case DOUBLE -> JdbcCodecs.DOUBLE;
      case DECIMAL ->
          javaType == BigInteger.class ? JdbcCodecs.BIG_INTEGER : JdbcCodecs.BIG_DECIMAL;
      case CHARACTER -> JdbcCodecs.CHARACTER;
      case VARCHAR -> JdbcCodecs.STRING;
      case VARBINARY -> JdbcCodecs.BYTES;
      case UUID -> JdbcCodecs.UUID;
      case DATE -> javaType == LocalDate.class ? JdbcCodecs.LOCAL_DATE : JdbcCodecs.SQL_DATE;
      case TIME -> javaType == LocalTime.class ? JdbcCodecs.LOCAL_TIME : JdbcCodecs.SQL_TIME;
      case TIME_WITH_TIME_ZONE -> JdbcCodecs.OFFSET_TIME;
      case TIMESTAMP ->
          javaType == LocalDateTime.class ? JdbcCodecs.LOCAL_DATE_TIME : JdbcCodecs.SQL_TIMESTAMP;
      case TIMESTAMP_WITH_TIME_ZONE ->
          javaType == Instant.class ? JdbcCodecs.INSTANT : JdbcCodecs.OFFSET_DATE_TIME;
      case OTHER -> null;
    };
  }
}
