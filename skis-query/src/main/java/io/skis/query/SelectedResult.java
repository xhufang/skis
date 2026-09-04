package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query result target kept independent from the FROM root until final scope validation. */
final class SelectedResult<S, R> {

  private final QueryTable<S> table;
  private final EntityPlanSet<S> plans;
  private final @Nullable Projection<S, R> projection;

  private SelectedResult(
      QueryTable<S> table, EntityPlanSet<S> plans, @Nullable Projection<S, R> projection) {
    this.table = Objects.requireNonNull(table, "table");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.projection = projection;
    if (projection != null) {
      projection.validateFrom(table);
    }
  }

  static <S> SelectedResult<S, S> entity(QueryTable<S> table, EntityPlanSet<S> plans) {
    return new SelectedResult<>(table, plans, null);
  }

  static <S, R> SelectedResult<S, R> projection(
      QueryTable<S> table, EntityPlanSet<S> plans, Projection<S, R> projection) {
    return new SelectedResult<>(table, plans, Objects.requireNonNull(projection, "projection"));
  }

  QueryPlanCompiler.Selection<R> selection() {
    return projection == null
        ? entitySelection(plans.compiler(), plans, table)
        : plans.compiler().projectionSelection(plans.model(), table, projection);
  }

  boolean belongsTo(QueryTable<?> candidate) {
    return table == candidate;
  }

  CompiledQueryPlan<R, Object> fastPlan(@Nullable QueryPredicate<?> predicate) {
    return projection == null
        ? entityFastPlan(plans, table, predicate)
        : projectionFastPlan(plans, table, projection, predicate);
  }

  String structuralIdentity() {
    return projection == null
        ? "entity:" + plans.entity().javaType().getName()
        : "projection:"
            + projection.resultType().getName()
            + ':'
            + projection.mapping().mappingType().getName();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <S, R> QueryPlanCompiler.Selection<R> entitySelection(
      QueryPlanCompiler compiler, EntityPlanSet<S> plans, QueryTable<S> table) {
    return (QueryPlanCompiler.Selection) compiler.entitySelection(plans.model(), table);
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
}
