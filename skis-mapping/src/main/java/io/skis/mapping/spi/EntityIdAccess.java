package io.skis.mapping.spi;

import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Generated direct primary-key reads and successful-write application or immutable copying. */
public interface EntityIdAccess<E> {

  @Nullable Object read(E entity) throws SQLException;

  E apply(E entity, Object id) throws SQLException;
}
