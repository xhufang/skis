package io.skis.sql.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stable query-block identity derived only from deterministic AST traversal positions. */
public record QueryBlockPath(List<QueryBlockLocation> locations) {

  private static final QueryBlockPath ROOT = new QueryBlockPath(List.of());

  public QueryBlockPath {
    Objects.requireNonNull(locations, "locations");
    locations = List.copyOf(locations);
    locations.forEach(location -> Objects.requireNonNull(location, "query block location"));
  }

  /** Returns the stable root query-block path. */
  public static QueryBlockPath root() {
    return ROOT;
  }

  /** Appends one deterministic embedding location without mutating this path. */
  public QueryBlockPath child(QueryBlockLocation location) {
    Objects.requireNonNull(location, "location");
    List<QueryBlockLocation> result = new ArrayList<>(locations.size() + 1);
    result.addAll(locations);
    result.add(location);
    return new QueryBlockPath(result);
  }

  @Override
  public String toString() {
    if (locations.isEmpty()) {
      return "$";
    }
    StringBuilder result = new StringBuilder("$");
    for (QueryBlockLocation location : locations) {
      result.append('/').append(location);
    }
    return result.toString();
  }
}
