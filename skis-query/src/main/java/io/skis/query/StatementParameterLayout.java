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
 * sharing this statement layout. Reusing one parameter inside a block then reuses its logical
 * slot, while embedding the same block twice can allocate distinct statement slots backed by the
 * same query-level reference.
 */
final class StatementParameterLayout {

  private final List<QueryColumn<?, ?>> parameterColumns = new ArrayList<>();
  private final List<QueryParameter<?>> parameterReferences = new ArrayList<>();
  private final List<ParameterSlot<?>> parameterSlots = new ArrayList<>();

  QueryBlock newQueryBlock() {
    return new QueryBlock();
  }

  List<QueryColumn<?, ?>> parameterColumns() {
    return List.copyOf(parameterColumns);
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

    <E, V> ParameterSlot<V> parameter(
        QueryColumn<E, V> column, QueryParameter<V> parameter) {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(parameter, "parameter");
      if (!column.javaType().equals(parameter.javaType())) {
        throw new QueryValidationException(
            "query parameter Java type "
                + parameter.javaType().getTypeName()
                + " does not match property '"
                + column.property().name()
                + "' type "
                + column.javaType().getTypeName());
      }
      RegisteredParameter registered = parameters.get(parameter);
      if (registered != null) {
        ParameterSlot<?> existing = registered.slot();
        if (!existing.javaType().equals(column.javaType())
            || existing.sqlType() != column.sqlType()
            || existing.nullability() != parameter.nullability()) {
          throw new QueryValidationException(
              "query parameter is used with conflicting Java type, SQL type or nullability");
        }
        if (registered.binderSource().property() != column.property()) {
          throw new QueryValidationException(
              "query parameter is reused with conflicting property Codec sources '"
                  + registered.binderSource().property().name()
                  + "' and '"
                  + column.property().name()
                  + "'");
        }
        @SuppressWarnings("unchecked")
        ParameterSlot<V> typed = (ParameterSlot<V>) existing;
        return typed;
      }
      int ordinal = parameterReferences.size();
      ParameterSlot<V> slot =
          new ParameterSlot<>(
              ordinal, column.javaType(), column.sqlType(), parameter.nullability());
      parameters.put(parameter, new RegisteredParameter(slot, column));
      parameterColumns.add(column);
      parameterReferences.add(parameter);
      parameterSlots.add(slot);
      return slot;
    }
  }

  private record RegisteredParameter(
      ParameterSlot<?> slot, QueryColumn<?, ?> binderSource) {

    private RegisteredParameter {
      Objects.requireNonNull(slot, "slot");
      Objects.requireNonNull(binderSource, "binderSource");
    }
  }
}
