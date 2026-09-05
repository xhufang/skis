package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.RowLayout;
import io.skis.sql.ast.SqlExpression;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query result target kept independent from the FROM root until final scope validation. */
final class SelectedResult<S, R> {

  private final QueryTable<S> table;
  private final EntityPlanSet<S> plans;
  private final @Nullable Projection<S, R> projection;
  private final Kind kind;

  private SelectedResult(
      QueryTable<S> table,
      EntityPlanSet<S> plans,
      @Nullable Projection<S, R> projection,
      Kind kind) {
    this.table = Objects.requireNonNull(table, "table");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.projection = projection;
    this.kind = Objects.requireNonNull(kind, "kind");
    if (projection != null) {
      projection.validateFrom(table);
    }
  }

  static <S> SelectedResult<S, S> entity(QueryTable<S> table, EntityPlanSet<S> plans) {
    return new SelectedResult<>(table, plans, null, Kind.REQUIRED_ENTITY);
  }

  static <S> SelectedResult<S, S> nullableEntity(QueryTable<S> table, EntityPlanSet<S> plans) {
    return new SelectedResult<>(table, plans, null, Kind.NULLABLE_ENTITY);
  }

  static <S, R> SelectedResult<S, R> requiredScalar(
      QueryTable<S> table, EntityPlanSet<S> plans, Projection<S, R> projection) {
    return new SelectedResult<>(
        table, plans, Objects.requireNonNull(projection, "projection"), Kind.REQUIRED_SCALAR);
  }

  static <S, R> SelectedResult<S, R> nullableScalar(
      QueryTable<S> table, EntityPlanSet<S> plans, Projection<S, R> projection) {
    return new SelectedResult<>(
        table, plans, Objects.requireNonNull(projection, "projection"), Kind.NULLABLE_SCALAR);
  }

  static <S, R> SelectedResult<S, R> projection(
      QueryTable<S> table, EntityPlanSet<S> plans, Projection<S, R> projection) {
    return new SelectedResult<>(
        table, plans, Objects.requireNonNull(projection, "projection"), Kind.LEGACY_PROJECTION);
  }

  private QueryPlanCompiler.Selection<R> selection() {
    if (kind == Kind.NULLABLE_ENTITY) {
      return nullableEntitySelection(plans, table);
    }
    return projection == null
        ? entitySelection(plans.compiler(), plans, table)
        : plans.compiler().projectionSelection(plans.model(), table, projection);
  }

  QueryPlanCompiler.Selection<R> resolve(TableRuntimeScope scope) {
    TableRuntimeScope.Occurrence<S> occurrence = scope.require(table);
    if (occurrence.model() != plans.model()) {
      throw new QueryValidationException(
          occurrence.description() + " does not use the selected target's canonical runtime model");
    }
    if (kind == Kind.NULLABLE_ENTITY) {
      if (plans.entity().primaryKey().isEmpty()) {
        throw new QueryValidationException(
            "selectNullable(table) requires complete non-null primary-key metadata for "
                + occurrence.description());
      }
      return nullableEntitySelection(plans, table);
    }
    if (scope.isNullExtended(table)) {
      switch (kind) {
        case REQUIRED_ENTITY ->
            throw nullableSelectionFailure(occurrence, "entity", "selectNullable(table)");
        case REQUIRED_SCALAR ->
            throw nullableSelectionFailure(occurrence, "scalar", "selectNullable(column)");
        case LEGACY_PROJECTION ->
            throw nullableSelectionFailure(
                occurrence, "projection", "a nullable generated result shape");
        case NULLABLE_SCALAR -> {
          // These result contracts explicitly preserve SQL NULL.
        }
      }
    }
    return selection();
  }

  List<SqlExpression<?>> expressions() {
    return projection == null
        ? List.copyOf(table.selections())
        : plans.compiler().projectionSelection(plans.model(), table, projection).expressions();
  }

  boolean belongsTo(QueryTable<?> candidate) {
    return table == candidate;
  }

  CompiledQueryPlan<R, Object> fastPlan(@Nullable QueryPredicate<?> predicate) {
    if (!supportsFastPath()) {
      throw new IllegalStateException(
          "nullable entity selection does not use an existing Fast Path");
    }
    return projection == null
        ? entityFastPlan(plans, table, predicate)
        : projectionFastPlan(plans, table, projection, predicate);
  }

  String structuralIdentity() {
    return projection == null
        ? (kind == Kind.NULLABLE_ENTITY ? "nullable-entity:" : "entity:")
            + plans.entity().javaType().getName()
        : "projection:"
            + projection.resultType().getName()
            + ':'
            + projection.mapping().mappingType().getName()
            + ':'
            + kind;
  }

  boolean supportsFastPath() {
    return kind != Kind.NULLABLE_ENTITY;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <S, R> QueryPlanCompiler.Selection<R> entitySelection(
      QueryPlanCompiler compiler, EntityPlanSet<S> plans, QueryTable<S> table) {
    return (QueryPlanCompiler.Selection) compiler.entitySelection(plans.model(), table);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <S, R> QueryPlanCompiler.Selection<R> nullableEntitySelection(
      EntityPlanSet<S> plans, QueryTable<S> table) {
    RowLayout layout = RowLayout.contiguous(plans.model().properties().size(), 1);
    return (QueryPlanCompiler.Selection)
        QueryPlanCompiler.Selection.of(
            table.selections(), plans.model().nullableRowDecoder(layout));
  }

  private static QueryValidationException nullableSelectionFailure(
      TableRuntimeScope.Occurrence<?> occurrence, String resultKind, String nullableEntry) {
    return new QueryValidationException(
        "non-null "
            + resultKind
            + " selection references null-extended "
            + occurrence.description()
            + "; use "
            + nullableEntry);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <S, R> CompiledQueryPlan<R, Object> entityFastPlan(
      EntityPlanSet<S> plans, QueryTable<S> table, @Nullable QueryPredicate<?> predicate) {
    return (CompiledQueryPlan) plans.selectPlan(table, (QueryPredicate) predicate);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <S, R> CompiledQueryPlan<R, Object> projectionFastPlan(
      EntityPlanSet<S> plans,
      QueryTable<S> table,
      Projection<S, R> projection,
      @Nullable QueryPredicate<?> predicate) {
    return plans.projectionPlan(table, projection, (QueryPredicate) predicate);
  }

  private enum Kind {
    REQUIRED_ENTITY,
    NULLABLE_ENTITY,
    REQUIRED_SCALAR,
    NULLABLE_SCALAR,
    LEGACY_PROJECTION
  }
}
