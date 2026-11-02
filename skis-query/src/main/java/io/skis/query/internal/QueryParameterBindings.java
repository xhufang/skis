package io.skis.query.internal;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Statement compilation collector kept separate from the structural slot layout. */
final class QueryParameterBindings {

  private QueryParameters first = QueryParameters.empty();
  private QueryParameters.@Nullable Builder parameters;

  void include(QueryParameters included) {
    Objects.requireNonNull(included, "included");
    if (included.isEmpty() || included == first) {
      return;
    }
    QueryParameters.Builder builder = parameters;
    if (builder == null) {
      if (first.isEmpty()) {
        // The common single-source statement reuses that environment without any copy.
        first = included;
        return;
      }
      builder = QueryParameters.builder();
      builder.include(first);
      parameters = builder;
    }
    builder.include(included);
  }

  QueryParameters parameters() {
    QueryParameters.Builder builder = parameters;
    return builder == null ? first : builder.build();
  }
}
