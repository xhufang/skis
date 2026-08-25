package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.h2.H2Dialect;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetMeta;
import io.skis.testmodel.pet.skis.PetTable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SkisExecutorReadSliceTest {

  @Test
  void executesGeneratedFindByIdAndDslMappingsThroughOneInjectedFacade() {
    JdbcCapture capture = new JdbcCapture();
    SkisExecutor executor = SkisExecutorFactory.create(capture.dataSource(), H2Dialect.INSTANCE);

    Pet fastPath = executor.findById(PetMeta.ENTITY, 7L).orElseThrow();
    PetTable pet = PetTable.PET;
    List<Pet> dsl = executor.selectFrom(pet).where(pet.name().eq("Mimi")).fetchList();
    String maliciousName = "Mimi' OR 1=1 --";
    List<String> projected =
        executor.select(pet.name()).from(pet).where(pet.name().eq(maliciousName)).fetchList();

    assertEquals(7L, fastPath.id());
    assertEquals("Mimi", fastPath.name());
    assertEquals(new BigDecimal("12.50"), fastPath.weight());
    assertTrue(fastPath.adopted());
    assertEquals(Long.valueOf(3L), fastPath.version());
    assertEquals(List.of(fastPath), dsl);
    assertEquals(List.of("Mimi"), projected);
    assertEquals(List.of(7L, "Mimi", maliciousName), capture.boundValues);
    assertEquals(3, capture.sql.size());
    assertTrue(capture.sql.get(0).endsWith("WHERE \"pet\".\"id\" = ?"));
    assertTrue(capture.sql.get(1).endsWith("WHERE \"pet\".\"pet_name\" = ?"));
    assertTrue(capture.sql.get(2).startsWith("SELECT \"pet\".\"pet_name\""));
    assertFalse(capture.sql.stream().anyMatch(sql -> sql.contains(maliciousName)));
    assertEquals(3, capture.resultSetCloses.get());
    assertEquals(3, capture.statementCloses.get());
    assertEquals(3, capture.connectionCloses.get());
  }

  private static final class JdbcCapture {

    private final List<String> sql = new ArrayList<>();
    private final List<Object> boundValues = new ArrayList<>();
    private final AtomicInteger connectionCloses = new AtomicInteger();
    private final AtomicInteger statementCloses = new AtomicInteger();
    private final AtomicInteger resultSetCloses = new AtomicInteger();

    private DataSource dataSource() {
      return proxy(
          DataSource.class,
          (ignored, method, arguments) ->
              method.getName().equals("getConnection") && method.getParameterCount() == 0
                  ? connection()
                  : defaultValue(method.getReturnType()));
    }

    private Connection connection() {
      return proxy(
          Connection.class,
          (ignored, method, arguments) -> {
            if (method.getName().equals("prepareStatement")) {
              sql.add((String) arguments[0]);
              return statement();
            }
            if (method.getName().equals("close")) {
              connectionCloses.incrementAndGet();
              return null;
            }
            return defaultValue(method.getReturnType());
          });
    }

    private PreparedStatement statement() {
      return proxy(
          PreparedStatement.class,
          (ignored, method, arguments) -> {
            if (method.getName().startsWith("set") && arguments != null && arguments.length >= 2) {
              boundValues.add(arguments[1]);
              return null;
            }
            if (method.getName().equals("executeQuery")) {
              return resultSet();
            }
            if (method.getName().equals("close")) {
              statementCloses.incrementAndGet();
              return null;
            }
            return defaultValue(method.getReturnType());
          });
    }

    private ResultSet resultSet() {
      AtomicInteger cursor = new AtomicInteger(-1);
      return proxy(
          ResultSet.class,
          (ignored, method, arguments) -> {
            int index = arguments == null || arguments.length == 0 ? 0 : (Integer) arguments[0];
            return switch (method.getName()) {
              case "next" -> cursor.incrementAndGet() == 0;
              case "getLong" -> index == 1 ? 7L : 3L;
              case "getString" -> "Mimi";
              case "getBigDecimal" -> new BigDecimal("12.50");
              case "getBoolean" -> true;
              case "wasNull" -> false;
              case "close" -> {
                resultSetCloses.incrementAndGet();
                yield null;
              }
              default -> defaultValue(method.getReturnType());
            };
          });
    }
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
    throw new AssertionError(type);
  }
}
