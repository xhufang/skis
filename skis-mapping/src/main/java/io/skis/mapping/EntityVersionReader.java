package io.skis.mapping;

import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Generated reflection-free reader for an entity's optional optimistic-version value. */
@FunctionalInterface
public interface EntityVersionReader<E> {

  /** Reads the source entity version without performing database access. */
  @Nullable Object read(E entity) throws SQLException;
}
