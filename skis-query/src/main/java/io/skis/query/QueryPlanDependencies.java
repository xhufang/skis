package io.skis.query;

import io.skis.dialect.spi.ResolvedStatement;
import io.skis.metadata.EntityMeta;
import io.skis.sql.ast.ResolvedSourceIdentity;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Resolved entity occurrences for plan identity and a deduplicated set for invalidation. */
final class QueryPlanDependencies {

  private final List<EntityMeta<?>> entities;
  private final IdentityHashMap<EntityMeta<?>, Boolean> membership;
  private final List<SourceDependency> sources;

  private QueryPlanDependencies(List<SourceDependency> sources) {
    this.sources = List.copyOf(sources);
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("a query plan requires at least one entity dependency");
    }
    this.membership = new IdentityHashMap<>();
    List<EntityMeta<?>> entities = new ArrayList<>();
    for (SourceDependency source : sources) {
      if (membership.put(source.entity(), Boolean.TRUE) == null) {
        entities.add(source.entity());
      }
    }
    this.entities = List.copyOf(entities);
  }

  /** Collects root, Join, subquery, and derived-table entity sources from resolved occurrences. */
  static QueryPlanDependencies from(ResolvedStatement resolved) {
    Objects.requireNonNull(resolved, "resolved");
    List<SourceDependency> sources = new ArrayList<>();
    for (ResolvedStatement.SourceOccurrence occurrence : resolved.sourceOccurrences()) {
      occurrence
          .source()
          .entityTable()
          .ifPresent(
              table -> sources.add(new SourceDependency(occurrence.identity(), table.entity())));
    }
    return new QueryPlanDependencies(sources);
  }

  List<SourceDependency> sources() {
    return sources;
  }

  List<EntityMeta<?>> entities() {
    return entities;
  }

  boolean contains(EntityMeta<?> entity) {
    return membership.containsKey(Objects.requireNonNull(entity, "entity"));
  }

  boolean sameEntities(QueryPlanDependencies other) {
    Objects.requireNonNull(other, "other");
    if (entities.size() != other.entities.size()) {
      return false;
    }
    for (EntityMeta<?> entity : entities) {
      if (!other.membership.containsKey(entity)) {
        return false;
      }
    }
    return true;
  }

  record SourceDependency(ResolvedSourceIdentity source, EntityMeta<?> entity) {

    SourceDependency {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(entity, "entity");
    }
  }

  @Override
  public String toString() {
    return entities.stream().map(EntityMeta::entityName).toList().toString();
  }
}
