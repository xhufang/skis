package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.PageRequest;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Internal value-bearing request used while compiling one terminal operation. */
sealed interface QueryPagination
    permits QueryPagination.None,
        QueryPagination.LimitOnly,
        QueryPagination.Offset,
        QueryPagination.Keyset {

  static Offset page(PageRequest request) {
    return new Offset(request.pageSize(), (long) request.pageIndex() * request.pageSize());
  }

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
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "offset must not be negative");
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
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "limit must be positive");
    }
  }
}
