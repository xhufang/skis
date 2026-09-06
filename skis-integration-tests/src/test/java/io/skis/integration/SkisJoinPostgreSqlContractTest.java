package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.skis.dialect.Dialect;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Runs the complete Join contract and resource checks against real PostgreSQL. */
class SkisJoinPostgreSqlContractTest extends AbstractSkisJoinContractTest {

  private TrackingDataSource trackingDataSource;

  @Override
  protected DataSource createDataSource() {
    String url = System.getenv("SKIS_POSTGRES_URL");
    assumeTrue(url != null && url.startsWith("jdbc:postgresql:"));
    PGSimpleDataSource delegate = new PGSimpleDataSource();
    delegate.setURL(url);
    delegate.setUser(environmentOrDefault("SKIS_POSTGRES_USER", "skis"));
    delegate.setPassword(environmentOrDefault("SKIS_POSTGRES_PASSWORD", "skis"));
    trackingDataSource = new TrackingDataSource(delegate);
    return trackingDataSource;
  }

  @Override
  protected Dialect dialect() {
    return PostgreSqlDialect.INSTANCE;
  }

  @Test
  void closesProjectionResultSetStatementAndConnection() {
    trackingDataSource.reset();

    assertEquals(5, projectionQuery().fetchList().size());

    assertEquals(1, trackingDataSource.closedResultSets);
    assertEquals(1, trackingDataSource.closedStatements);
    assertEquals(1, trackingDataSource.closedConnections);
  }

  @Test
  void executesFullJoinNullExtensionAgainstPostgreSql() {
    List<Long> ownerIds =
        executor
            .selectNullable(owner.id())
            .from(pet)
            .fullJoin(owner)
            .on(pet.ownerId().eq(owner.id()))
            .where(pet.id().in(petIds()).or(owner.id().in(ownerIds())))
            .fetchList();

    assertEquals(6, ownerIds.size());
    assertEquals(2, ownerIds.stream().filter(java.util.Objects::isNull).count());
    assertEquals(2, ownerIds.stream().filter(id -> Long.valueOf(ownerAdaId).equals(id)).count());
    assertEquals(1, ownerIds.stream().filter(id -> Long.valueOf(ownerGraceId).equals(id)).count());
    assertEquals(
        1,
        ownerIds.stream().filter(id -> Long.valueOf(ownerWithoutPetId).equals(id)).count());
  }

  private static String environmentOrDefault(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static final class TrackingDataSource implements DataSource {

    private final DataSource delegate;
    private int closedConnections;
    private int closedStatements;
    private int closedResultSets;

    private TrackingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    private void reset() {
      closedConnections = 0;
      closedStatements = 0;
      closedResultSets = 0;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return wrapConnection(delegate.getConnection(username, password));
    }

    private Connection wrapConnection(Connection connection) {
      return proxy(
          Connection.class,
          connection,
          (method, arguments) -> {
            if (method.getName().equals("close")) {
              closedConnections++;
            }
            Object result = invoke(method, connection, arguments);
            return result instanceof PreparedStatement statement
                ? wrapStatement(statement)
                : result;
          });
    }

    private PreparedStatement wrapStatement(PreparedStatement statement) {
      return proxy(
          PreparedStatement.class,
          statement,
          (method, arguments) -> {
            if (method.getName().equals("close")) {
              closedStatements++;
            }
            Object result = invoke(method, statement, arguments);
            return result instanceof ResultSet resultSet ? wrapResultSet(resultSet) : result;
          });
    }

    private ResultSet wrapResultSet(ResultSet resultSet) {
      return proxy(
          ResultSet.class,
          resultSet,
          (method, arguments) -> {
            if (method.getName().equals("close")) {
              closedResultSets++;
            }
            return invoke(method, resultSet, arguments);
          });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
        Class<T> type, T delegate, ThrowingInvocation invocation) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (ignored, method, arguments) -> invocation.invoke(method, arguments));
    }

    private static Object invoke(Method method, Object target, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException exception) {
        throw exception.getCause();
      }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }

  @FunctionalInterface
  private interface ThrowingInvocation {

    Object invoke(Method method, Object[] arguments) throws Throwable;
  }
}
