package io.skis.query;

import io.skis.sql.ast.ParameterSlot;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Assigns final statement-level logical slots while keeping reuse local to one query-block
 * occurrence.
 *
 * <p>Nested-query traversal can create a fresh {@link QueryBlock} for every occurrence while
 * sharing this statement layout. Reusing one parameter inside a block then reuses its logical slot,
 * while embedding the same block twice can allocate distinct statement slots backed by the same
 * query-level reference.
 */
final class StatementParameterLayout {

  private final List<Selectable<?>> parameterSources = new ArrayList<>();
  private final List<QueryParameter<?>> parameterReferences = new ArrayList<>();
  private final List<ParameterSlot<?>> parameterSlots = new ArrayList<>();

  QueryBlock newQueryBlock() {
    return new QueryBlock();
  }

  List<Selectable<?>> parameterSources() {
    return List.copyOf(parameterSources);
  }

  List<QueryParameter<?>> parameterReferences() {
    return List.copyOf(parameterReferences);
  }

  List<ParameterSlot<?>> parameterSlots() {
    return List.copyOf(parameterSlots);
  }

  final class QueryBlock {

    private final IdentityHashMap<QueryParameter<?>, RegisteredParameter> parameters =
        new IdentityHashMap<>();

    <V> ParameterSlot<V> parameter(Selectable<V> source, QueryParameter<V> parameter) {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(parameter, "parameter");
      if (!source.javaType().equals(parameter.javaType())) {
        throw new QueryValidationException(
            "query parameter Java type "
                + parameter.javaType().getTypeName()
                + " does not match expression '"
                + SelectableSupport.summary(source)
                + "' type "
                + source.javaType().getTypeName());
      }
      RegisteredParameter registered = parameters.get(parameter);
      if (registered != null) {
        ParameterSlot<?> existing = registered.slot();
        if (!existing.javaType().equals(source.javaType())
            || existing.sqlType() != source.sqlType()
            || existing.nullability() != parameter.nullability()) {
          throw new QueryValidationException(
              "query parameter is used with conflicting Java type, SQL type or nullability");
        }
        if (!SelectableSupport.sameOccurrence(registered.binderSource(), source)) {
          throw new QueryValidationException(
              "query parameter is reused with conflicting expression Codec sources '"
                  + SelectableSupport.summary(registered.binderSource())
                  + "' and '"
                  + SelectableSupport.summary(source)
                  + "'");
        }
        @SuppressWarnings("unchecked")
        ParameterSlot<V> typed = (ParameterSlot<V>) existing;
        return typed;
      }
      int ordinal = parameterReferences.size();
      ParameterSlot<V> slot =
          new ParameterSlot<>(
              ordinal, source.javaType(), source.sqlType(), parameter.nullability());
      parameters.put(parameter, new RegisteredParameter(slot, source));
      parameterSources.add(source);
      parameterReferences.add(parameter);
      parameterSlots.add(slot);
      return slot;
    }
  }

  private record RegisteredParameter(ParameterSlot<?> slot, Selectable<?> binderSource) {

    private RegisteredParameter {
      Objects.requireNonNull(slot, "slot");
      Objects.requireNonNull(binderSource, "binderSource");
    }
  }
}
