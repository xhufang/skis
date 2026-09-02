package io.skis.sql.ast;

import java.util.Objects;

/** Keyset seek predicate plus the parameterized content limit. */
public record KeysetSeek(SqlPredicate predicate, ParameterSlot<Integer> limit)
    implements SelectPagination {

  public KeysetSeek {
    Objects.requireNonNull(predicate, "predicate");
    Objects.requireNonNull(limit, "limit");
    if (limit.javaType() != Integer.class
        || limit.sqlType() != SqlType.INTEGER
        || limit.nullability() != Nullability.NON_NULL) {
      throw new IllegalArgumentException("limit must use a non-null Integer parameter");
    }
  }
}
