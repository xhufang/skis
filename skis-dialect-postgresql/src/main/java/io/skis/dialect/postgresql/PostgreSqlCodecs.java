package io.skis.dialect.postgresql;

import io.skis.mapping.JdbcReadContext;
import io.skis.mapping.JdbcTypeCodec;
import io.skis.mapping.JdbcWriteContext;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.jspecify.annotations.Nullable;

/** PostgreSQL-specific codecs that supplement the database-neutral {@code JdbcCodecs}. */
public final class PostgreSqlCodecs {

  /**
   * String-based JSON/JSONB codec.
   *
   * <p>Values are always bound as JDBC {@link Types#OTHER}; they are never interpolated into SQL.
   * PostgreSQL remains responsible for JSON syntax validation and conversion to the target JSON or
   * JSONB column type.
   */
  public static final JdbcTypeCodec<String> JSON =
      new JdbcTypeCodec<>() {
        @Override
        public @Nullable String read(
            ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
          return readJson(resultSet, index, context);
        }

        @Override
        public void bind(
            PreparedStatement statement,
            int index,
            @Nullable String value,
            JdbcWriteContext context)
            throws SQLException {
          bindJson(statement, index, value, context);
        }
      };

  private PostgreSqlCodecs() {}

  /** Reads JSON or JSONB as its textual representation. */
  public static @Nullable String readJson(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    return resultSet.getString(index);
  }

  /** Binds JSON text without requiring a compile-time dependency on the PostgreSQL driver. */
  public static void bindJson(
      PreparedStatement statement,
      int index,
      @Nullable String value,
      JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setObject(index, value, Types.OTHER);
    }
  }
}
