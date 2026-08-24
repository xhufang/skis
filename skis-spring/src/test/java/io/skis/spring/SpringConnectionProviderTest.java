package io.skis.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.skis.core.ExecutionContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SpringConnectionProviderTest {

  @Test
  void delegatesConnectionOwnershipToSpringDataSourceUtilities() throws Exception {
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
    DataSource dataSource =
        proxy(
            DataSource.class,
            (ignored, method, arguments) ->
                method.getName().equals("getConnection")
                    ? connection
                    : defaultValue(method.getReturnType()));
    SpringConnectionProvider provider = new SpringConnectionProvider(dataSource);

    Connection acquired = provider.acquire(ExecutionContext.EMPTY);
    provider.release(acquired, ExecutionContext.EMPTY);

    assertSame(connection, acquired);
    assertEquals(1, closes.get());
    assertFalse(provider.supportsLocalTransactions());
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
