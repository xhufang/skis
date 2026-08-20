package io.skis.mapping;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Reads and binds one Java/JDBC value type. */
public interface JdbcTypeCodec<T> {

  /** Reads a value by one-based JDBC column index. */
  @Nullable T read(ResultSet resultSet, int index, JdbcReadContext context) throws SQLException;

  /** Binds a value by one-based JDBC parameter index. */
  void bind(
      PreparedStatement statement,
      int index,
      @Nullable T value,
      JdbcWriteContext context)
      throws SQLException;
}
