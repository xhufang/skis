package io.skis.sql.ast;

import java.util.Objects;

/** Parameterized LIMIT without an OFFSET. */
public record Limit(ParameterSlot<Integer> limit) implements SelectPagination {

  public Limit {
    Objects.requireNonNull(limit, "limit");
    if (limit.javaType() != Integer.class
        || limit.sqlType() != SqlType.INTEGER
        || limit.nullability() != Nullability.NON_NULL) {
      throw new IllegalArgumentException("limit must use a non-null Integer parameter");
    }
  }
}
