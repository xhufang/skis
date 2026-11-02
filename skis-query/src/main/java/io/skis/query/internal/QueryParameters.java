package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Internal immutable runtime values keyed by opaque {@link QueryParameter} identity. */
final class QueryParameters {

  private static final QueryParameters EMPTY = new QueryParameters(new IdentityHashMap<>());

  private volatile @Nullable Map<QueryParameter<?>, Binding> bindings;
  private final @Nullable QueryParameters left;
  private final @Nullable QueryParameters right;

  private QueryParameters(IdentityHashMap<QueryParameter<?>, Binding> bindings) {
    this.bindings = Collections.unmodifiableMap(bindings);
    this.left = null;
    this.right = null;
  }

  private QueryParameters(QueryParameters left, QueryParameters right) {
    this.left = left;
    this.right = right;
  }

  /** Materializes one identity table only when a complete input is actually consumed. */
  private Map<QueryParameter<?>, Binding> bindings() {
    Map<QueryParameter<?>, Binding> existing = bindings;
    if (existing != null) return existing;
    synchronized (this) {
      existing = bindings;
      if (existing != null) return existing;
      IdentityHashMap<QueryParameter<?>, Binding> flattened = new IdentityHashMap<>();
      ArrayDeque<QueryParameters> pending = new ArrayDeque<>();
      IdentityHashMap<QueryParameters, Boolean> visited = new IdentityHashMap<>();
      pending.push(this);
      while (!pending.isEmpty()) {
        QueryParameters current = pending.pop();
        if (visited.put(current, Boolean.TRUE) != null) continue;
        Map<QueryParameter<?>, Binding> leaf = current.bindings;
        if (leaf == null) {
          pending.push(Objects.requireNonNull(current.right));
          pending.push(Objects.requireNonNull(current.left));
        } else {
          leaf.forEach(
              (parameter, binding) -> {
                Binding previous = flattened.putIfAbsent(parameter, binding);
                if (previous != null && previous != binding) throw duplicate(parameter);
              });
        }
      }
      existing = Collections.unmodifiableMap(flattened);
      bindings = existing;
      return existing;
    }
  }

  static QueryParameters empty() {
    return EMPTY;
  }

  static <V> QueryParameters of(QueryParameter<V> parameter, @Nullable V value) {
    Objects.requireNonNull(parameter, "parameter");
    IdentityHashMap<QueryParameter<?>, Binding> bindings = new IdentityHashMap<>(1);
    bindings.put(parameter, capture(parameter, value));
    return new QueryParameters(bindings);
  }

  /** Imports an already-owned write snapshot without copying a mutable JDBC value twice. */
  static <V> QueryParameters ofOwnedSnapshot(QueryParameter<V> parameter, @Nullable V value) {
    validateValue(parameter, value);
    IdentityHashMap<QueryParameter<?>, Binding> bindings = new IdentityHashMap<>(1);
    bindings.put(parameter, new Binding(value));
    return new QueryParameters(bindings);
  }

  static Builder builder() {
    return new Builder();
  }

  boolean isEmpty() {
    Map<QueryParameter<?>, Binding> existing = bindings;
    return existing != null && existing.isEmpty();
  }

  /** Returns a value-redacted diagnostic summary. */
  @Override
  public String toString() {
    return "QueryParameters[size=" + bindings().size() + "]";
  }

  QueryParameters merge(QueryParameters other) {
    Objects.requireNonNull(other, "other");
    if (this == other || other.isEmpty()) return this;
    if (isEmpty()) return other;
    return new QueryParameters(this, other);
  }

  /** Replaces exclusively owned bindings with one environment copy and no value recapture. */
  QueryParameters replace(Set<QueryParameter<?>> removed, QueryParameters replacement) {
    if (removed.isEmpty()) {
      return merge(replacement);
    }
    IdentityHashMap<QueryParameter<?>, Binding> merged = new IdentityHashMap<>(bindings());
    removed.forEach(merged::remove);
    replacement
        .bindings()
        .forEach(
            (parameter, binding) -> {
              Binding existing = merged.putIfAbsent(parameter, binding);
              if (existing != null && existing != binding) {
                throw duplicate(parameter);
              }
            });
    return merged.isEmpty() ? EMPTY : new QueryParameters(merged);
  }

  /** Records only replacement references which have no owner in the retained environment. */
  Set<QueryParameter<?>> replacementOnlyReferences(
      Set<QueryParameter<?>> removed, QueryParameters replacement) {
    Map<QueryParameter<?>, Binding> retained = bindings();
    Set<QueryParameter<?>> exclusive = null;
    for (QueryParameter<?> parameter : replacement.bindings().keySet()) {
      if (!retained.containsKey(parameter) || removed.contains(parameter)) {
        if (exclusive == null) {
          exclusive = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        exclusive.add(parameter);
      }
    }
    return exclusive == null ? Set.of() : Collections.unmodifiableSet(exclusive);
  }

  /** Releases exclusive ownership when another clause captures the same binding references. */
  Set<QueryParameter<?>> withoutBoundReferences(Set<QueryParameter<?>> references) {
    if (references.isEmpty()) {
      return references;
    }
    Map<QueryParameter<?>, Binding> bound = bindings();
    if (bound.isEmpty()) {
      return references;
    }
    Set<QueryParameter<?>> remaining = null;
    Set<QueryParameter<?>> candidates =
        bound.size() < references.size() ? bound.keySet() : references;
    for (QueryParameter<?> reference : candidates) {
      if (references.contains(reference) && bound.containsKey(reference)) {
        if (remaining == null) {
          remaining = Collections.newSetFromMap(new IdentityHashMap<>());
          remaining.addAll(references);
        }
        remaining.remove(reference);
      }
    }
    if (remaining == null) {
      return references;
    }
    return remaining.isEmpty() ? Set.of() : Collections.unmodifiableSet(remaining);
  }

  /**
   * Validates the environment against every parameter declared by the reusable query description.
   *
   * <p>This is deliberately separate from statement value projection: content, count, and lowered
   * statements may each retain only a subset of the description's parameters.
   */
  void validateFor(List<QueryParameter<?>> declaredParameters) {
    Objects.requireNonNull(declaredParameters, "declaredParameters");
    Map<QueryParameter<?>, Binding> supplied = bindings();
    IdentityHashMap<QueryParameter<?>, Boolean> declared = new IdentityHashMap<>();
    for (QueryParameter<?> parameter : declaredParameters) {
      Objects.requireNonNull(parameter, "parameter");
      declared.put(parameter, Boolean.TRUE);
      if (!supplied.containsKey(parameter)) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_MISSING, "missing binding for " + describe(parameter));
      }
    }
    for (QueryParameter<?> bound : supplied.keySet()) {
      if (!declared.containsKey(bound)) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_DECLARATION,
            "binding for " + describe(bound) + " is not declared by the query description");
      }
    }
  }

  /** Projects captured values directly into the logical slots retained by one final statement. */
  QueryArguments valuesFor(List<QueryParameter<?>> parameters) {
    return QueryArguments.fromProjectedValues(projectValues(parameters));
  }

  /** Projects captured bindings into a fresh array owned by the consuming statement. */
  @Nullable Object[] projectValues(List<QueryParameter<?>> parameters) {
    Objects.requireNonNull(parameters, "parameters");
    Map<QueryParameter<?>, Binding> supplied = bindings();
    @Nullable Object[] values = new @Nullable Object[parameters.size()];
    for (int index = 0; index < parameters.size(); index++) {
      QueryParameter<?> parameter = parameters.get(index);
      Objects.requireNonNull(parameter, "parameter");
      Binding binding = supplied.get(parameter);
      if (binding == null) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_MISSING, "missing binding for " + describe(parameter));
      }
      values[index] = binding.value();
    }
    return values;
  }

  private static <V> Binding capture(QueryParameter<V> parameter, @Nullable V value) {
    validateValue(parameter, value);
    return new Binding(value == null ? null : QueryValueSnapshots.capture(value));
  }

  private static <V> void validateValue(QueryParameter<V> parameter, @Nullable V value) {
    Objects.requireNonNull(parameter, "parameter");
    if (!(parameter instanceof DefaultQueryParameter<?>)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_DECLARATION, "parameter was not created by SKIS");
    }
    if (value == null) {
      if (!parameter.nullability().isNullable()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_VALUE, "null is not allowed for " + describe(parameter));
      }
      return;
    }
    if (!parameter.javaType().isInstance(value)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_VALUE,
          SkisDiagnostics.builder(SkisErrorCode.QUERY_PARAMETER_VALUE.defaultStage())
              .types(parameter.javaType().getTypeName(), value.getClass().getTypeName())
              .detail(describe(parameter))
              .build());
    }
  }

  private static QueryValidationException duplicate(QueryParameter<?> parameter) {
    return QueryValidationException.of(
        SkisErrorCode.QUERY_PARAMETER_DECLARATION, "duplicate binding for " + describe(parameter));
  }

  private static String describe(QueryParameter<?> parameter) {
    return "query parameter of type " + parameter.javaType().getTypeName();
  }

  private record Binding(@Nullable Object value) {}

  /** Package-owned capture helper used while constructing ordinary value predicates. */
  static final class Builder {

    private IdentityHashMap<QueryParameter<?>, Binding> bindings;
    // Set once build() has published the current table; the next mutation copies it first.
    private boolean published;

    private Builder() {
      this.bindings = new IdentityHashMap<>();
    }

    private IdentityHashMap<QueryParameter<?>, Binding> owned() {
      if (published) {
        bindings = new IdentityHashMap<>(bindings);
        published = false;
      }
      return bindings;
    }

    void include(QueryParameters included) {
      IdentityHashMap<QueryParameter<?>, Binding> bindings = owned();
      included
          .bindings()
          .forEach(
              (parameter, binding) -> {
                Binding previous = bindings.putIfAbsent(parameter, binding);
                if (previous != null && previous != binding) {
                  throw duplicate(parameter);
                }
              });
    }

    /** Captures one value immediately and associates it with the reference by object identity. */
    <V> Builder bind(QueryParameter<V> parameter, @Nullable V value) {
      Objects.requireNonNull(parameter, "parameter");
      IdentityHashMap<QueryParameter<?>, Binding> bindings = owned();
      if (bindings.containsKey(parameter)) {
        throw duplicate(parameter);
      }
      bindings.put(parameter, capture(parameter, value));
      return this;
    }

    /** Builds an immutable parameter environment without copying the collected table. */
    QueryParameters build() {
      if (bindings.isEmpty()) {
        return EMPTY;
      }
      published = true;
      return new QueryParameters(bindings);
    }
  }
}
