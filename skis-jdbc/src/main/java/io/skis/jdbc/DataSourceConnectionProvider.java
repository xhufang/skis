package io.skis.jdbc;

import io.skis.core.ExecutionContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Connection provider that obtains connections from a JDBC {@link DataSource}. */
public final class DataSourceConnectionProvider implements ConnectionProvider {

  private final DataSource dataSource;

  /** Creates a provider backed by the supplied data source. */
  public DataSourceConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public Connection acquire(ExecutionContext context) throws SQLException {
    Objects.requireNonNull(context, "context");
    Connection connection = dataSource.getConnection();
    if (connection == null) {
      throw new SQLException("DataSource returned a null Connection");
    }
    return connection;
  }

  @Override
  public void release(Connection connection, ExecutionContext context) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(context, "context");
    connection.close();
  }
}
