package io.skis.dialect.postgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.skis.mapping.JdbcReadContext;
import io.skis.mapping.JdbcWriteContext;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PostgreSqlCodecsTest {

  @Test
  void readsJsonAsTextWithoutAProprietaryDriverType() throws Exception {
    String json = "{\"name\":\"Milo\"}";
    AtomicInteger requestedIndex = new AtomicInteger(-1);
    ResultSet resultSet =
        (ResultSet)
            Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (ignored, method, arguments) -> {
                  if (method.getName().equals("getString")) {
                    requestedIndex.set((Integer) arguments[0]);
                    return json;
                  }
                  return defaultValue(method.getReturnType());
                });

    assertEquals(json, PostgreSqlCodecs.JSON.read(resultSet, 3, JdbcReadContext.EMPTY));
    assertEquals(3, requestedIndex.get());
  }

  @Test
  void preservesSqlLookingJsonAsOneBoundParameter() throws Exception {
    String json = "{\"name\":\"Milo'); DROP TABLE pet; --\"}";
    AtomicReference<String> invocation = new AtomicReference<>();
    AtomicReference<Object[]> arguments = new AtomicReference<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, values) -> {
                  if (method.getName().equals("setObject")) {
                    invocation.set(method.getName());
                    arguments.set(values);
                  }
                  return defaultValue(method.getReturnType());
                });

    PostgreSqlCodecs.JSON.bind(statement, 2, json, JdbcWriteContext.EMPTY);

    assertEquals("setObject", invocation.get());
    assertArrayEquals(new Object[] {2, json, Types.OTHER}, arguments.get());
  }

  @Test
  void bindsNullJsonWithThePostgreSqlOtherType() throws Exception {
    AtomicReference<Object[]> arguments = new AtomicReference<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, values) -> {
                  if (method.getName().equals("setNull")) {
                    arguments.set(values);
                  }
                  return defaultValue(method.getReturnType());
                });

    PostgreSqlCodecs.JSON.bind(statement, 4, null, JdbcWriteContext.EMPTY);

    assertArrayEquals(new Object[] {4, Types.OTHER}, arguments.get());

    ResultSet resultSet =
        (ResultSet)
            Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (ignored, method, values) -> defaultValue(method.getReturnType()));
    assertNull(PostgreSqlCodecs.JSON.read(resultSet, 4, JdbcReadContext.EMPTY));
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
