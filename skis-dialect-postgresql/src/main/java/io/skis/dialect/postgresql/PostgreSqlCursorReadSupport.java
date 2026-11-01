package io.skis.dialect.postgresql;

import io.skis.dialect.spi.CursorReadSupport;
import java.sql.Connection;
import java.sql.SQLException;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;

/** PgJDBC prepared statements require extended protocol to fetch through a server portal. */
enum PostgreSqlCursorReadSupport implements CursorReadSupport {
  INSTANCE;

  @Override
  public int defaultFetchSize() {
    return 256;
  }

  @Override
  public boolean supportsConnection(Connection connection) throws SQLException {
    return connection.isWrapperFor(PgConnection.class)
        && connection.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE;
  }
}
