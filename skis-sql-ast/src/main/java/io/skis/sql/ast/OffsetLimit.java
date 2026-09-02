package io.skis.sql.ast;

import java.util.Objects;

/** Parameterized LIMIT/OFFSET pagination. */
public record OffsetLimit(ParameterSlot<Integer> limit, ParameterSlot<Long> offset)
    implements SelectPagination {

  public OffsetLimit {
    Objects.requireNonNull(limit, "limit");
    Objects.requireNonNull(offset, "offset");
    requireDescriptor(limit, Integer.class, SqlType.INTEGER, "limit");
    requireDescriptor(offset, Long.class, SqlType.BIGINT, "offset");
  }

  private static void requireDescriptor(
      ParameterSlot<?> slot, Class<?> javaType, SqlType sqlType, String name) {
    if (slot.javaType() != javaType
        || slot.sqlType() != sqlType
        || slot.nullability() != Nullability.NON_NULL) {
      throw new IllegalArgumentException(name + " must use a non-null " + javaType.getSimpleName());
    }
  }
}
