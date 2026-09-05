package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.sql.ast.SqlExpression;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query result target kept independent from the FROM root until final scope validation. */
final class SelectedResult<S, R> {

  private final @Nullable QueryTable<S> table;
  private final @Nullable EntityPlanSet<S> plans;
  private final @Nullable QueryColumn<S, R> scalar;
  private final @Nullable ProjectionSelection<R> projection;
  private final Kind kind;

  private SelectedResult(
      @Nullable QueryTable<S> table,
      @Nullable EntityPlanSet<S> plans,
      @Nullable QueryColumn<S, R> scalar,
      @Nullable ProjectionSelection<R> projection,
      Kind kind) {
    this.table = table;
    this.plans = plans;
    this.scalar = scalar;
    this.projection = projection;
    this.kind = Objects.requireNonNull(kind, "kind");
    if (kind == Kind.GENERATED_PROJECTION) {
      Objects.requireNonNull(projection, "projection");
      if (table != null || plans != null || scalar != null) {
        throw new IllegalArgumentException(
            "a generated projection result must not be bound to one selected table");
      }
    } else {
      Objects.requireNonNull(table, "table");
      Objects.requireNonNull(plans, "plans");
      if (projection != null) {
        throw new IllegalArgumentException(
            "an entity or scalar result must not carry a generated projection mapping");
      }
    }
  }

  static <S> SelectedResult<S, S> entity(QueryTable<S> table, EntityPlanSet<S> plans) {
    return new SelectedResult<>(table, plans, null, null, Kind.REQUIRED_ENTITY);
  }

  static <S> SelectedResult<S, S> nullableEntity(QueryTable<S> table, EntityPlanSet<S> plans) {
    return new SelectedResult<>(table, plans, null, null, Kind.NULLABLE_ENTITY);
  }

  static <S, R> SelectedResult<S, R> requiredScalar(
      QueryTable<S> table, EntityPlanSet<S> plans, QueryColumn<S, R> column) {
    return new SelectedResult<>(
        table, plans, Objects.requireNonNull(column, "column"), null, Kind.REQUIRED_SCALAR);
  }

  static <S, R> SelectedResult<S, R> nullableScalar(
      QueryTable<S> table, EntityPlanSet<S> plans, QueryColumn<S, R> column) {
    return new SelectedResult<>(
        table, plans, Objects.requireNonNull(column, "column"), null, Kind.NULLABLE_SCALAR);
  }

  static <R> SelectedResult<R, R> projection(ProjectionSelection<R> selection) {
    return new SelectedResult<>(
        null,
        null,
        null,
        Objects.requireNonNull(selection, "selection"),
        Kind.GENERATED_PROJECTION);
  }

  ResolvedResultShape<R> resolve(TableRuntimeScope scope) {
    Objects.requireNonNull(scope, "scope");
    return switch (kind) {
      case REQUIRED_ENTITY -> entityShape(scope, false);
      case NULLABLE_ENTITY -> entityShape(scope, true);
      case REQUIRED_SCALAR ->
          ResolvedResultShape.scalar(requireTable(), requirePlans(), requireScalar(), scope, false);
      case NULLABLE_SCALAR ->
          ResolvedResultShape.scalar(requireTable(), requirePlans(), requireScalar(), scope, true);
      case GENERATED_PROJECTION -> ResolvedResultShape.projection(requireProjection(), scope);
    };
  }

  List<SqlExpression<?>> expressions() {
    if (kind == Kind.GENERATED_PROJECTION) {
      return requireProjection().selections().stream()
          .<SqlExpression<?>>map(Selectable::expression)
          .toList();
    }
    QueryColumn<S, R> selectedScalar = scalar;
    return selectedScalar == null
        ? List.copyOf(requireTable().selections())
        : List.of(selectedScalar.expression());
  }

  boolean belongsTo(QueryTable<?> candidate) {
    return table == candidate;
  }

  CompiledQueryPlan<R, Object> fastPlan(@Nullable QueryPredicate<?> predicate) {
    if (!supportsFastPath()) {
      throw new IllegalStateException("only complete non-null entity selections use a Fast Path");
    }
    return entityFastPlan(requirePlans(), requireTable(), predicate);
  }

  String structuralIdentity() {
    return switch (kind) {
      case REQUIRED_ENTITY -> "entity:" + requirePlans().entity().javaType().getName();
      case NULLABLE_ENTITY -> "nullable-entity:" + requirePlans().entity().javaType().getName();
      case REQUIRED_SCALAR -> scalarIdentity("scalar:");
      case NULLABLE_SCALAR -> scalarIdentity("nullable-scalar:");
      case GENERATED_PROJECTION -> "projection:" + requireProjection().mappingId();
    };
  }

  /**
   * Returns the one expression that can preserve this DISTINCT result in an automatic count, or
   * {@code null} when a single-table complete entity can safely use {@code COUNT(*)}.
   */
  @Nullable SqlExpression<?> automaticDistinctCountExpression(boolean hasJoins) {
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY -> {
        var primaryKey = requirePlans().entity().primaryKey().orElse(null);
        if (primaryKey == null) {
          if (requireTable().selections().size() == 1) {
            yield requireTable().selections().getFirst();
          }
          throw new QueryValidationException(
              "automatic count cannot preserve a multi-expression distinct complete entity "
                  + "without primary-key metadata; provide an explicit count query");
        }
        if (!hasJoins) {
          yield null;
        }
        if (primaryKey.composite()) {
          throw new QueryValidationException(
              "automatic count cannot preserve a distinct complete entity with a composite "
                  + "primary key after JOIN; provide an explicit count query");
        }
        int ordinal = primaryKey.properties().getFirst().ordinal();
        yield requireTable().selections().get(ordinal);
      }
      case REQUIRED_SCALAR, NULLABLE_SCALAR -> requireScalar().expression();
      case GENERATED_PROJECTION -> {
        List<SqlExpression<?>> expressions = expressions();
        if (expressions.size() != 1) {
          throw new QueryValidationException(
              "automatic count cannot preserve a multi-expression distinct result; provide an "
                  + "explicit count query");
        }
        yield expressions.getFirst();
      }
    };
  }

  boolean supportsFastPath() {
    return kind == Kind.REQUIRED_ENTITY;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ResolvedResultShape<R> entityShape(TableRuntimeScope scope, boolean nullable) {
    return (ResolvedResultShape)
        ResolvedResultShape.entity(requireTable(), requirePlans(), scope, nullable);
  }

  private String scalarIdentity(String prefix) {
    QueryColumn<S, R> column = requireScalar();
    return prefix
        + column.table().entity().javaType().getName()
        + ':'
        + column.property().ordinal();
  }

  private QueryTable<S> requireTable() {
    return Objects.requireNonNull(table, "selected table");
  }

  private EntityPlanSet<S> requirePlans() {
    return Objects.requireNonNull(plans, "selected table plans");
  }

  private QueryColumn<S, R> requireScalar() {
    return Objects.requireNonNull(scalar, "selected scalar");
  }

  private ProjectionSelection<R> requireProjection() {
    return Objects.requireNonNull(projection, "projection selection");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <S, R> CompiledQueryPlan<R, Object> entityFastPlan(
      EntityPlanSet<S> plans, QueryTable<S> table, @Nullable QueryPredicate<?> predicate) {
    return (CompiledQueryPlan) plans.selectPlan(table, (QueryPredicate) predicate);
  }

  private enum Kind {
    REQUIRED_ENTITY,
    NULLABLE_ENTITY,
    REQUIRED_SCALAR,
    NULLABLE_SCALAR,
    GENERATED_PROJECTION
  }
}
