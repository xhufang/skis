package io.skis.spring;

import io.skis.core.ExecutionContext;
import io.skis.jdbc.ConnectionProvider;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Connection provider that reuses Spring transaction-bound DataSource connections.
 *
 * <p>Transaction completion remains owned by Spring. This provider only participates in Spring's
 * connection reference counting and never commits or rolls back an acquired connection.
 */
public final class SpringConnectionProvider implements ConnectionProvider {

  private final DataSource dataSource;

  public SpringConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public boolean supportsLocalTransactions() {
    return false;
  }

  @Override
  public Connection acquire(ExecutionContext context) throws SQLException {
    Objects.requireNonNull(context, "context");
    try {
      return DataSourceUtils.getConnection(dataSource);
    } catch (org.springframework.jdbc.CannotGetJdbcConnectionException failure) {
      if (failure.getCause() instanceof SQLException sqlFailure) {
        throw sqlFailure;
      }
      throw new SQLException("Spring could not acquire a DataSource connection", failure);
    }
  }

  @Override
  public void release(Connection connection, ExecutionContext context) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(context, "context");
    DataSourceUtils.doReleaseConnection(connection, dataSource);
  }
}
