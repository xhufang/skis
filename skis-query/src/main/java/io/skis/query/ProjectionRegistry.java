package io.skis.query;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable registry of APT-generated projection definitions keyed by user result type. */
public final class ProjectionRegistry {

  private final Map<Class<?>, Projection<?, ?>> projections;

  private ProjectionRegistry(Map<Class<?>, Projection<?, ?>> projections) {
    this.projections = Collections.unmodifiableMap(projections);
  }

  /** Creates a registry and rejects duplicate projection result types. */
  public static ProjectionRegistry of(Collection<? extends Projection<?, ?>> projections) {
    Objects.requireNonNull(projections, "projections");
    Map<Class<?>, Projection<?, ?>> indexed = new LinkedHashMap<>();
    for (Projection<?, ?> projection : projections) {
      Projection<?, ?> value = Objects.requireNonNull(projection, "projection");
      Projection<?, ?> previous = indexed.put(value.resultType(), value);
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate generated projection for result type '"
                + value.resultType().getTypeName()
                + "'");
      }
    }
    return new ProjectionRegistry(indexed);
  }

  /** Returns an empty registry. */
  public static ProjectionRegistry empty() {
    return new ProjectionRegistry(new LinkedHashMap<>());
  }

  /** Finds a generated projection by its user-owned result type. */
  public Optional<Projection<?, ?>> find(Class<?> resultType) {
    return Optional.ofNullable(projections.get(Objects.requireNonNull(resultType, "resultType")));
  }

  /** Resolves and validates a projection for the caller's typed table expression. */
  @SuppressWarnings("unchecked")
  public <E, R> Projection<E, R> require(QueryTable<E> table, Class<R> resultType) {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(resultType, "resultType");
    Projection<?, ?> untyped = projections.get(resultType);
    if (untyped == null) {
      throw new QueryValidationException(
          "no generated projection is registered for result type '"
              + resultType.getTypeName()
              + "'");
    }
    if (untyped.entity() != table.entity()) {
      throw new QueryValidationException(
          "projection result type '"
              + resultType.getTypeName()
              + "' belongs to entity '"
              + untyped.entity().entityName()
              + "' but query table belongs to entity '"
              + table.entity().entityName()
              + "'");
    }
    return (Projection<E, R>) untyped;
  }

  /** Returns the number of registered generated projections. */
  public int size() {
    return projections.size();
  }

  /** Returns an immutable snapshot of registered generated projections. */
  public Collection<Projection<?, ?>> projections() {
    return projections.values();
  }
}
