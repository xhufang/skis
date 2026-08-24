package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.ExecutionContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DataSourceConnectionProviderTest {

  @Test
  void acquiresAndReleasesExactlyTheDataSourceConnection() throws Exception {
    AtomicInteger closes = new AtomicInteger();
    Connection connection =
        proxy(
            Connection.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("close")) {
                closes.incrementAndGet();
                return null;
              }
              return defaultValue(method.getReturnType());
            });
    AtomicInteger acquisitions = new AtomicInteger();
    DataSource dataSource =
        proxy(
            DataSource.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("getConnection") && method.getParameterCount() == 0) {
                acquisitions.incrementAndGet();
                return connection;
              }
              return defaultValue(method.getReturnType());
            });
    ConnectionProvider provider = new DataSourceConnectionProvider(dataSource);

    Connection acquired = provider.acquire(ExecutionContext.EMPTY);
    provider.release(acquired, ExecutionContext.EMPTY);

    assertSame(connection, acquired);
    assertEquals(1, acquisitions.get());
    assertEquals(1, closes.get());
  }

  @Test
  void propagatesAcquireFailureWithoutDiscardingJdbcDetails() throws Exception {
    SQLException failure = new SQLException("pool exhausted", "08001", 17);
    DataSource dataSource =
        proxy(
            DataSource.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("getConnection") && method.getParameterCount() == 0) {
                throw failure;
              }
              return defaultValue(method.getReturnType());
            });
    ConnectionProvider provider = new DataSourceConnectionProvider(dataSource);

    SQLException thrown =
        assertThrows(SQLException.class, () -> provider.acquire(ExecutionContext.EMPTY));

    assertSame(failure, thrown);
    assertEquals("08001", thrown.getSQLState());
    assertEquals(17, thrown.getErrorCode());
  }

  @Test
  void rejectsBrokenDataSourceThatReturnsNull() throws Exception {
    DataSource dataSource =
        proxy(
            DataSource.class, (ignored, method, arguments) -> defaultValue(method.getReturnType()));
    ConnectionProvider provider = new DataSourceConnectionProvider(dataSource);

    SQLException failure =
        assertThrows(SQLException.class, () -> provider.acquire(ExecutionContext.EMPTY));

    assertEquals("DataSource returned a null Connection", failure.getMessage());
  }

  @Test
  void propagatesReleaseFailureWithoutDiscardingJdbcDetails() throws Exception {
    SQLException failure = new SQLException("close failed", "08006", 29);
    Connection connection =
        proxy(
            Connection.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("close")) {
                throw failure;
              }
              return defaultValue(method.getReturnType());
            });
    DataSource dataSource =
        proxy(
            DataSource.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("getConnection") && method.getParameterCount() == 0) {
                return connection;
              }
              return defaultValue(method.getReturnType());
            });
    ConnectionProvider provider = new DataSourceConnectionProvider(dataSource);

    SQLException thrown =
        assertThrows(
            SQLException.class, () -> provider.release(connection, ExecutionContext.EMPTY));

    assertSame(failure, thrown);
    assertEquals("08006", thrown.getSQLState());
    assertEquals(29, thrown.getErrorCode());
  }

  @Test
  void rejectsNullCollaboratorsAtTheBoundary() throws Exception {
    assertThrows(NullPointerException.class, () -> new DataSourceConnectionProvider(null));

    Connection connection = proxy(Connection.class, (ignored, method, arguments) -> null);
    DataSource dataSource =
        proxy(
            DataSource.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("getConnection") && method.getParameterCount() == 0) {
                return connection;
              }
              return defaultValue(method.getReturnType());
            });
    ConnectionProvider provider = new DataSourceConnectionProvider(dataSource);

    assertThrows(NullPointerException.class, () -> provider.acquire(null));
    assertThrows(NullPointerException.class, () -> provider.release(null, ExecutionContext.EMPTY));
    assertThrows(NullPointerException.class, () -> provider.release(connection, null));
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (type == void.class) {
      return null;
    }
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    throw new AssertionError("unsupported primitive return type " + type.getName());
  }
}
