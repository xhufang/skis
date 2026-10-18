package io.skis.query;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  static Builder builder() {
    return new Builder();
  }

  <V> QueryParameters bind(QueryParameter<V> parameter, @Nullable V value) {
    Objects.requireNonNull(parameter, "parameter");
    if (bindings.containsKey(parameter)) {
      throw duplicate(parameter);
    }
    Binding captured = capture(parameter, value);
    IdentityHashMap<QueryParameter<?>, Binding> appended = new IdentityHashMap<>(bindings);
    appended.put(parameter, captured);
    return new QueryParameters(appended);
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
    IdentityHashMap<QueryParameter<?>, Binding> merged = new IdentityHashMap<>(bindings);
    other.bindings.forEach(
        (parameter, binding) -> {
          Binding existing = merged.putIfAbsent(parameter, binding);
          if (existing != null && existing != binding) {
            throw duplicate(parameter);
          }
        });
    return new QueryParameters(merged);
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
    return QueryArguments.fromProjectedValues(values);
  }

  private static <V> Binding capture(QueryParameter<V> parameter, @Nullable V value) {
    Objects.requireNonNull(parameter, "parameter");
    if (value == null) {
      if (!parameter.nullability().isNullable()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_VALUE, "null is not allowed for " + describe(parameter));
      }
      return new Binding(null);
    }
    if (!parameter.javaType().isInstance(value)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_VALUE,
          SkisDiagnostics.builder(SkisErrorCode.QUERY_PARAMETER_VALUE.defaultStage())
              .types(parameter.javaType().getTypeName(), value.getClass().getTypeName())
              .detail(describe(parameter))
              .build());
    }
    return new Binding(QueryValueSnapshots.capture(value));
  }

  private static QueryValidationException duplicate(QueryParameter<?> parameter) {
    return QueryValidationException.of(
        SkisErrorCode.QUERY_PARAMETER_DECLARATION,
        "duplicate binding for " + describe(parameter));
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

  private QueryParameters parameters = QueryParameters.empty();

  void include(QueryParameters included) {
    parameters = parameters.merge(Objects.requireNonNull(included, "included"));
  }

  QueryParameters parameters() {
    return parameters;
  }
}
