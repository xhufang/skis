package io.skis.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable runtime values keyed by opaque {@link QueryParameter} identity. */
public final class QueryParameters {

  private static final QueryParameters EMPTY = new QueryParameters(new IdentityHashMap<>());

  private final Map<QueryParameter<?>, Binding> bindings;

  private QueryParameters(IdentityHashMap<QueryParameter<?>, Binding> bindings) {
    this.bindings = Collections.unmodifiableMap(new IdentityHashMap<>(bindings));
  }

  /** Returns the empty parameter environment. */
  public static QueryParameters empty() {
    return EMPTY;
  }

  /** Creates an environment containing one captured binding. */
  public static <V> QueryParameters of(QueryParameter<V> parameter, @Nullable V value) {
    return QueryParameters.builder().bind(parameter, value).build();
  }

  /** Returns a builder that rejects repeated bindings of the same reference. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a new environment with one binding added. */
  public <V> QueryParameters bind(QueryParameter<V> parameter, @Nullable V value) {
    Builder builder = new Builder(bindings);
    builder.bind(parameter, value);
    return builder.build();
  }

  /** Returns the number of parameter references bound by this environment. */
  public int size() {
    return bindings.size();
  }

  /** Returns whether this environment has no bindings. */
  public boolean isEmpty() {
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
        throw new QueryValidationException("missing binding for " + describe(parameter));
      }
    }
    for (QueryParameter<?> bound : bindings.keySet()) {
      if (!declared.containsKey(bound)) {
        throw new QueryValidationException(
            "binding for " + describe(bound) + " is not declared by the query description");
      }
    }
  }

  /** Returns captured values for the logical slots retained by one final statement. */
  List<@Nullable Object> valuesFor(List<QueryParameter<?>> parameters) {
    Objects.requireNonNull(parameters, "parameters");
    List<@Nullable Object> values = new ArrayList<>(parameters.size());
    for (QueryParameter<?> parameter : parameters) {
      Objects.requireNonNull(parameter, "parameter");
      Binding binding = bindings.get(parameter);
      if (binding == null) {
        throw new QueryValidationException("missing binding for " + describe(parameter));
      }
      values.add(binding.value());
    }
    return Collections.unmodifiableList(values);
  }

  private static <V> Binding capture(QueryParameter<V> parameter, @Nullable V value) {
    Objects.requireNonNull(parameter, "parameter");
    if (value == null) {
      if (!parameter.nullability().isNullable()) {
        throw new QueryValidationException(
            "null is not allowed for " + describe(parameter));
      }
      return new Binding(null);
    }
    if (!parameter.javaType().isInstance(value)) {
      throw new QueryValidationException(
          describe(parameter)
              + " requires "
              + parameter.javaType().getTypeName()
              + " but received "
              + value.getClass().getTypeName());
    }
    return new Binding(QueryValueSnapshots.capture(value));
  }

  private static QueryValidationException duplicate(QueryParameter<?> parameter) {
    return new QueryValidationException("duplicate binding for " + describe(parameter));
  }

  private static String describe(QueryParameter<?> parameter) {
    return parameter
        .diagnosticName()
        .map(name -> "query parameter '" + name + "'")
        .orElse("anonymous query parameter of type " + parameter.javaType().getTypeName());
  }

  private record Binding(@Nullable Object value) {}

  /** Mutable capture helper whose {@link #build()} result is independent and immutable. */
  public static final class Builder {

    private final IdentityHashMap<QueryParameter<?>, Binding> bindings;

    private Builder() {
      this.bindings = new IdentityHashMap<>();
    }

    private Builder(Map<QueryParameter<?>, Binding> existing) {
      this.bindings = new IdentityHashMap<>(existing);
    }

    /** Captures one value immediately and associates it with the reference by object identity. */
    public <V> Builder bind(QueryParameter<V> parameter, @Nullable V value) {
      Objects.requireNonNull(parameter, "parameter");
      if (bindings.containsKey(parameter)) {
        throw duplicate(parameter);
      }
      bindings.put(parameter, capture(parameter, value));
      return this;
    }

    /** Builds an immutable parameter environment. */
    public QueryParameters build() {
      return bindings.isEmpty() ? EMPTY : new QueryParameters(bindings);
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
