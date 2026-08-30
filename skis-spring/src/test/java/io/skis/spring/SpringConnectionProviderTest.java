package io.skis.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionContext;
import io.skis.core.TransactionException;
import io.skis.jdbc.JdbcTransaction;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SpringConnectionProviderTest {

  @Test
  void delegatesNonTransactionalConnectionOwnershipToSpringUtilities() throws Exception {
    AtomicInteger closes = new AtomicInteger();
    Connection connection =
        proxy(
            Connection.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("close")) {
                closes.incrementAndGet();
              }
              return defaultValue(method.getReturnType());
            });
    DataSource dataSource = dataSourceReturning(connection);
    SpringConnectionProvider provider = new SpringConnectionProvider(dataSource);

    Connection acquired = provider.acquire(ExecutionContext.EMPTY);
    provider.release(acquired, ExecutionContext.EMPTY);

    assertSame(connection, acquired);
    assertEquals(1, closes.get());
    assertFalse(provider.supportsLocalTransactions());
  }

  @Test
  void reusesAndDefersClosingTheSpringTransactionBoundConnection() throws Exception {
    JdbcDataSource dataSource = database();
    SpringConnectionProvider provider = new SpringConnectionProvider(dataSource);
    TransactionTemplate transactions =
        new TransactionTemplate(new JdbcTransactionManager(dataSource));
    AtomicReference<Connection> transactionConnection = new AtomicReference<>();

    transactions.executeWithoutResult(
        status -> {
          Connection first = acquire(provider);
          Connection second = acquire(provider);
          assertSame(first, second);
          transactionConnection.set(first);
          executeUpdate(first, "INSERT INTO pet(id, pet_name) VALUES (1, 'Mimi')");
          release(provider, first);
          release(provider, second);
          assertFalse(closed(first));
        });

    assertTrue(closed(transactionConnection.get()));
    assertEquals(1, countRows(dataSource));
  }

  @Test
  void leavesRollbackOwnershipWithTheSpringTransactionManager() throws Exception {
    JdbcDataSource dataSource = database();
    SpringConnectionProvider provider = new SpringConnectionProvider(dataSource);
    TransactionTemplate transactions =
        new TransactionTemplate(new JdbcTransactionManager(dataSource));

    transactions.executeWithoutResult(
        status -> {
          Connection connection = acquire(provider);
          executeUpdate(connection, "INSERT INTO pet(id, pet_name) VALUES (2, 'Nori')");
          release(provider, connection);
          status.setRollbackOnly();
        });

    assertEquals(0, countRows(dataSource));
  }

  @Test
  void propagatesTheOriginalSqlFailureFromAcquireAndRelease() throws Exception {
    SQLException acquireFailure = new SQLException("acquire failed", "08001", 17);
    DataSource failingDataSource =
        proxy(
            DataSource.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("getConnection")) {
                throw acquireFailure;
              }
              return defaultValue(method.getReturnType());
            });
    SpringConnectionProvider failingProvider = new SpringConnectionProvider(failingDataSource);

    SQLException acquired =
        assertThrows(
            SQLException.class, () -> failingProvider.acquire(ExecutionContext.EMPTY));
    assertSame(acquireFailure, acquired);

    SQLException releaseFailure = new SQLException("release failed", "08006", 18);
    Connection failingConnection =
        proxy(
            Connection.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("close")) {
                throw releaseFailure;
              }
              return defaultValue(method.getReturnType());
            });
    SpringConnectionProvider releaseProvider =
        new SpringConnectionProvider(dataSourceReturning(failingConnection));

    SQLException released =
        assertThrows(
            SQLException.class,
            () -> releaseProvider.release(failingConnection, ExecutionContext.EMPTY));
    assertSame(releaseFailure, released);
  }

  @Test
  void rejectsSkisLocalTransactionsBeforeAcquiringASpringConnection() {
    AtomicInteger acquisitions = new AtomicInteger();
    DataSource dataSource =
        proxy(
            DataSource.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("getConnection")) {
                acquisitions.incrementAndGet();
              }
              return defaultValue(method.getReturnType());
            });
    SpringConnectionProvider provider = new SpringConnectionProvider(dataSource);

    assertThrows(TransactionException.class, () -> JdbcTransaction.begin(provider));
    assertEquals(0, acquisitions.get());
  }

  private static JdbcDataSource database() throws SQLException {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:skis_spring_"
            + UUID.randomUUID().toString().replace('-', '_')
            + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE pet (id BIGINT PRIMARY KEY, pet_name VARCHAR(200) NOT NULL)");
    }
    return dataSource;
  }

  private static int countRows(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pet");
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private static Connection acquire(SpringConnectionProvider provider) {
    try {
      return provider.acquire(ExecutionContext.EMPTY);
    } catch (SQLException failure) {
      throw new AssertionError("unexpected connection acquisition failure", failure);
    }
  }

  private static void release(SpringConnectionProvider provider, Connection connection) {
    try {
      provider.release(connection, ExecutionContext.EMPTY);
    } catch (SQLException failure) {
      throw new AssertionError("unexpected connection release failure", failure);
    }
  }

  private static void executeUpdate(Connection connection, String sql) {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    } catch (SQLException failure) {
      throw new AssertionError("unexpected SQL execution failure", failure);
    }
  }

  private static boolean closed(Connection connection) {
    try {
      return connection.isClosed();
    } catch (SQLException failure) {
      throw new AssertionError("cannot inspect connection state", failure);
    }
  }

  private static DataSource dataSourceReturning(Connection connection) {
    return proxy(
        DataSource.class,
        (ignored, method, arguments) ->
            method.getName().equals("getConnection")
                ? connection
                : defaultValue(method.getReturnType()));
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    if (type == char.class) {
      return '\0';
    }
    throw new AssertionError(type);
  }
}
