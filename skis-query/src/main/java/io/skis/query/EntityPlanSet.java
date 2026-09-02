package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jspecify.annotations.Nullable;

/** Fixed entity plan slots plus a shared bounded cache for dynamic projection shapes. */
final class EntityPlanSet<E> {

  private final EntityRuntimeModel<E> model;
  private final QueryPlanCompiler compiler;
  private final ProjectionPlanCache projectionPlans;
  private final RuntimeQueryTable<E> canonicalTable;
  private final AtomicReference<@Nullable CompiledQueryPlan<E, Object>> selectAll =
      new AtomicReference<>();
  private final AtomicReferenceArray<@Nullable CompiledQueryPlan<E, Object>> equalities;
  private final @Nullable PropertyMeta<E, ?> findByIdProperty;
  private final @Nullable CompiledQueryPlan<E, Object> findByIdPlan;

  EntityPlanSet(EntityRuntimeModel<E> model, QueryPlanCompiler compiler) {
    this(model, compiler, new ProjectionPlanCache(ProjectionPlanCache.DEFAULT_MAXIMUM_SIZE));
  }

  EntityPlanSet(
      EntityRuntimeModel<E> model,
      QueryPlanCompiler compiler,
      ProjectionPlanCache projectionPlans) {
    this.model = Objects.requireNonNull(model, "model");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    this.projectionPlans = Objects.requireNonNull(projectionPlans, "projectionPlans");
    this.canonicalTable = new RuntimeQueryTable<>(model.entity());
    this.equalities = new AtomicReferenceArray<>(model.entity().properties().size());
    this.findByIdProperty = resolveFindByIdProperty();
    this.findByIdPlan = findByIdProperty == null ? null : equalityPlan(findByIdProperty.ordinal());
  }

  EntityMeta<E> entity() {
    return model.entity();
  }

  EntityRuntimeModel<E> model() {
    return model;
  }

  QueryPlanCompiler compiler() {
    return compiler;
  }

  CompiledQueryPlan<E, Object> findByIdPlan() {
    CompiledQueryPlan<E, Object> plan = findByIdPlan;
    if (plan == null) {
      throw unsupportedFindById();
    }
    return plan;
  }

  PropertyMeta<E, ?> findByIdProperty() {
    PropertyMeta<E, ?> property = findByIdProperty;
    if (property == null) {
      throw unsupportedFindById();
    }
    return property;
  }

  CompiledQueryPlan<E, Object> selectPlan(
      QueryTable<E> table, @Nullable QueryPredicate<E> predicate) {
    if (predicate == null) {
      return table.alias().isEmpty() ? cachedSelectAll() : compiler.compile(model, table, null);
    }
    PropertyMeta<E, ?> property = predicate.simpleEqualityProperty(table);
    if (property == null) {
      return compiler.compileQuery(model, table, predicate);
    }
    return table.alias().isEmpty()
        ? equalityPlan(property.ordinal())
        : compiler.compileQuery(model, table, predicate);
  }

  <R> CompiledQueryPlan<R, Object> projectionPlan(
      QueryTable<E> table, Projection<E, R> projection, @Nullable QueryPredicate<E> predicate) {
    Objects.requireNonNull(projection, "projection").validateFrom(table);
    PropertyMeta<E, ?> property =
        predicate == null ? null : predicate.simpleEqualityProperty(table);
    if (table.alias().isPresent()) {
      return compiler.compileProjection(model, table, projection, predicate);
    }
    if (predicate != null && property == null) {
      return compiler.compileProjection(model, table, projection, predicate);
    }
    return projectionPlans.getOrCompile(
        model.entity(),
        projection,
        property,
        () -> compiler.compileProjection(model, table, projection, predicate));
  }

  Object argument(@Nullable QueryPredicate<E> predicate) {
    if (predicate == null) {
      return NoParameters.INSTANCE;
    }
    List<Object> arguments = predicate.compile().arguments();
    return arguments.isEmpty() ? NoParameters.INSTANCE : new QueryArguments(arguments);
  }

  private CompiledQueryPlan<E, Object> cachedSelectAll() {
    CompiledQueryPlan<E, Object> existing = selectAll.get();
    if (existing != null) {
      return existing;
    }
    CompiledQueryPlan<E, Object> compiled = compiler.compile(model, canonicalTable, null);
    CompiledQueryPlan<E, Object> published = selectAll.compareAndExchange(null, compiled);
    return published == null ? compiled : published;
  }

  private CompiledQueryPlan<E, Object> equalityPlan(int ordinal) {
    CompiledQueryPlan<E, Object> existing = equalities.get(ordinal);
    if (existing != null) {
      return existing;
    }
    PropertyMeta<E, ?> property = model.entity().properties().get(ordinal);
    CompiledQueryPlan<E, Object> compiled = compiler.compile(model, canonicalTable, property);
    CompiledQueryPlan<E, Object> published = equalities.compareAndExchange(ordinal, null, compiled);
    return published == null ? compiled : published;
  }

  private @Nullable PropertyMeta<E, ?> resolveFindByIdProperty() {
    return model
        .entity()
        .primaryKey()
        .filter(primaryKey -> primaryKey.properties().size() == 1)
        .map(primaryKey -> primaryKey.properties().getFirst())
        .orElse(null);
  }

  private QueryValidationException unsupportedFindById() {
    return new QueryValidationException(
        "findById requires exactly one primary-key property for entity '"
            + model.entity().entityName()
            + "'");
  }
}
