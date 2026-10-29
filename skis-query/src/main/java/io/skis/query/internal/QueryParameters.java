package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.query.QueryParameter;
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

  private final Map<QueryParameter<?>, Binding> bindings;

  private QueryParameters(IdentityHashMap<QueryParameter<?>, Binding> bindings) {
    this.bindings = Collections.unmodifiableMap(bindings);
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

  <V> QueryParameters bind(QueryParameter<V> parameter, @Nullable V value) {
    Objects.requireNonNull(parameter, "parameter");
    requireUnbound(parameter);
    Binding captured = capture(parameter, value);
    IdentityHashMap<QueryParameter<?>, Binding> appended = new IdentityHashMap<>(bindings);
    appended.put(parameter, captured);
    return new QueryParameters(appended);
  }

  void requireUnbound(QueryParameter<?> parameter) {
    if (bindings.containsKey(parameter)) {
      throw duplicate(parameter);
    }
  }

  int size() {
    return bindings.size();
  }

  boolean isEmpty() {
    return bindings.isEmpty();
  }

  /** Returns a value-redacted diagnostic summary. */
  @Override
  public String toString() {
    return "QueryParameters[size=" + bindings.size() + "]";
  }

  QueryParameters merge(QueryParameters other) {
    Objects.requireNonNull(other, "other");
    if (this == other) {
      return this;
    }
    if (other.isEmpty()) {
      return this;
    }
    if (isEmpty()) {
      return other;
    }
    IdentityHashMap<QueryParameter<?>, Binding> merged = null;
    for (Map.Entry<QueryParameter<?>, Binding> entry : other.bindings.entrySet()) {
      QueryParameter<?> parameter = entry.getKey();
      Binding existing = bindings.get(parameter);
      if (existing != null) {
        if (existing != entry.getValue()) {
          throw duplicate(parameter);
        }
      } else {
        if (merged == null) {
          merged = new IdentityHashMap<>(bindings);
        }
        merged.put(parameter, entry.getValue());
      }
    }
    return merged == null ? this : new QueryParameters(merged);
  }

  /** Replaces exclusively owned bindings with one environment copy and no value recapture. */
  QueryParameters replace(Set<QueryParameter<?>> removed, QueryParameters replacement) {
    if (removed.isEmpty()) {
      return merge(replacement);
    }
    IdentityHashMap<QueryParameter<?>, Binding> merged = new IdentityHashMap<>(bindings);
    removed.forEach(merged::remove);
    replacement.bindings.forEach(
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
    Set<QueryParameter<?>> exclusive = null;
    for (QueryParameter<?> parameter : replacement.bindings.keySet()) {
      if (!bindings.containsKey(parameter) || removed.contains(parameter)) {
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
    if (references.isEmpty() || bindings.isEmpty()) {
      return references;
    }
    Set<QueryParameter<?>> remaining = null;
    Set<QueryParameter<?>> candidates =
        bindings.size() < references.size() ? bindings.keySet() : references;
    for (QueryParameter<?> reference : candidates) {
      if (references.contains(reference) && bindings.containsKey(reference)) {
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
    IdentityHashMap<QueryParameter<?>, Boolean> declared = new IdentityHashMap<>();
    for (QueryParameter<?> parameter : declaredParameters) {
      Objects.requireNonNull(parameter, "parameter");
      declared.put(parameter, Boolean.TRUE);
      if (!bindings.containsKey(parameter)) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_MISSING, "missing binding for " + describe(parameter));
      }
    }
    for (QueryParameter<?> bound : bindings.keySet()) {
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
    @Nullable Object[] values = new @Nullable Object[parameters.size()];
    for (int index = 0; index < parameters.size(); index++) {
      QueryParameter<?> parameter = parameters.get(index);
      Objects.requireNonNull(parameter, "parameter");
      Binding binding = bindings.get(parameter);
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
    return parameter
        .diagnosticName()
        .map(name -> "query parameter '" + name + "'")
        .orElse("anonymous query parameter of type " + parameter.javaType().getTypeName());
  }

  private record Binding(@Nullable Object value) {}

  /** Package-owned capture helper used while constructing ordinary value predicates. */
  static final class Builder {

    private final IdentityHashMap<QueryParameter<?>, Binding> bindings;

    private Builder() {
      this.bindings = new IdentityHashMap<>();
    }

    void include(QueryParameters included) {
      included.bindings.forEach(
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
      if (bindings.containsKey(parameter)) {
        throw duplicate(parameter);
      }
      bindings.put(parameter, capture(parameter, value));
      return this;
    }

    /** Builds an immutable parameter environment. */
    QueryParameters build() {
      return bindings.isEmpty() ? EMPTY : new QueryParameters(new IdentityHashMap<>(bindings));
    }
  }
}

/** Statement compilation collector kept separate from the structural slot layout. */
final class QueryParameterBindings {

  private QueryParameters.@Nullable Builder parameters;

  void include(QueryParameters included) {
    Objects.requireNonNull(included, "included");
    if (included.isEmpty()) {
      return;
    }
    QueryParameters.Builder builder = parameters;
    if (builder == null) {
      builder = QueryParameters.builder();
      parameters = builder;
    }
    builder.include(included);
  }

  QueryParameters parameters() {
    QueryParameters.Builder builder = parameters;
    return builder == null ? QueryParameters.empty() : builder.build();
  }
}
