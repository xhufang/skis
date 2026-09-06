package io.skis.mapping;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * Reads and binds one Java/JDBC value type.
 *
 * <p>Custom value types used as immutable query-predicate parameters must themselves be deeply
 * immutable. The query DSL snapshots the mutable representations supported by built-in codecs
 * ({@code byte[]} and {@code java.sql.Date}/{@code Time}/{@code Timestamp}) when a predicate is
 * created, but cannot infer a safe copy operation for an arbitrary custom type. Implementations
 * must not mutate a value supplied to {@link #bind}.
 */
public interface JdbcTypeCodec<T> {

  /** Reads a value by one-based JDBC column index. */
  @Nullable T read(ResultSet resultSet, int index, JdbcReadContext context) throws SQLException;

  /** Binds a value by one-based JDBC parameter index. */
  void bind(PreparedStatement statement, int index, @Nullable T value, JdbcWriteContext context)
      throws SQLException;
}
