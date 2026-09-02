package io.skis.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Content and exact count returned only after both JDBC statements succeed. */
public record JdbcPageResult<R>(List<@Nullable R> items, long totalElements) {

  public JdbcPageResult {
    items = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(items, "items")));
    if (totalElements < 0) {
      throw new IllegalArgumentException("totalElements must not be negative");
    }
  }
}
