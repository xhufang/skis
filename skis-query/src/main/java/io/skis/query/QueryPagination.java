package io.skis.query;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Internal value-bearing request used while compiling one terminal operation. */
sealed interface QueryPagination
    permits QueryPagination.None,
        QueryPagination.LimitOnly,
        QueryPagination.Offset,
        QueryPagination.Keyset {

  enum None implements QueryPagination {
    INSTANCE
  }

  record LimitOnly(int limit) implements QueryPagination {
    public LimitOnly {
      requireLimit(limit);
    }
  }

  record Offset(int limit, long offset) implements QueryPagination {
    public Offset {
      requireLimit(limit);
      if (offset < 0) {
        throw new IllegalArgumentException("offset must not be negative");
      }
    }
  }

  record Keyset(int limit, List<@Nullable Object> values) implements QueryPagination {
    public Keyset {
      requireLimit(limit);
      // This request is invocation-local and consumed synchronously. The final-slot carrier takes
      // the retained values it needs, while persistent continuations keep their own deep snapshot.
      Objects.requireNonNull(values, "values");
    }
  }

  private static void requireLimit(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
  }
}
