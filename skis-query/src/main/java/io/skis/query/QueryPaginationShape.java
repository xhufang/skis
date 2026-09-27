package io.skis.query;

import java.util.List;
import java.util.Objects;

/** Immutable, value-free pagination identity shared by local and catalog plan caches. */
record QueryPaginationShape(Mode mode, List<Boolean> keysetNullMarkers) {

  private static final QueryPaginationShape NONE =
      new QueryPaginationShape(Mode.NONE, List.of());
  private static final QueryPaginationShape LIMIT =
      new QueryPaginationShape(Mode.LIMIT, List.of());
  private static final QueryPaginationShape OFFSET =
      new QueryPaginationShape(Mode.OFFSET, List.of());

  QueryPaginationShape {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(keysetNullMarkers, "keysetNullMarkers");
    keysetNullMarkers.forEach(marker -> Objects.requireNonNull(marker, "keyset null marker"));
    keysetNullMarkers = List.copyOf(keysetNullMarkers);
    if (mode == Mode.KEYSET && keysetNullMarkers.isEmpty()) {
      throw new IllegalArgumentException("keyset pagination requires at least one null marker");
    }
    if (mode != Mode.KEYSET && !keysetNullMarkers.isEmpty()) {
      throw new IllegalArgumentException("only keyset pagination can carry null markers");
    }
  }

  static QueryPaginationShape none() {
    return NONE;
  }

  static QueryPaginationShape keyset(List<Boolean> nullMarkers) {
    return new QueryPaginationShape(Mode.KEYSET, nullMarkers);
  }

  static QueryPaginationShape from(QueryPagination pagination) {
    Objects.requireNonNull(pagination, "pagination");
    return switch (pagination) {
      case QueryPagination.None ignored -> NONE;
      case QueryPagination.LimitOnly ignored -> LIMIT;
      case QueryPagination.Offset ignored -> OFFSET;
      case QueryPagination.Keyset keyset ->
          keyset(keyset.values().stream().map(Objects::isNull).toList());
    };
  }

  enum Mode {
    NONE,
    LIMIT,
    OFFSET,
    KEYSET
  }
}
