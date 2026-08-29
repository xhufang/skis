package io.skis.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionContext;
import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.jdbc.ConnectionProvider;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityMutationBinders;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MutationExecutionDiagnosticsTest {

  private static final String SENSITIVE_NAME = "secret-customer-value";
  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          TableMeta.of("pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void exposesSafeJdbcDiagnosticsAndOriginalFailuresFromMutationFacade() {
    SQLException executionFailure = new SQLException("execute failed", "42000", 71);
    SQLException releaseFailure = new SQLException("release failed", "08006", 72);
    AtomicInteger statementCloses = new AtomicInteger();
    AtomicInteger releases = new AtomicInteger();
    PreparedStatement statement =
        proxy(
            PreparedStatement.class,
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "executeUpdate" -> throw executionFailure;
                  case "close" -> {
                    statementCloses.incrementAndGet();
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
    Connection connection =
        proxy(
            Connection.class,
            (ignored, method, arguments) ->
                method.getName().equals("prepareStatement")
                    ? statement
                    : defaultValue(method.getReturnType()));
    ConnectionProvider provider =
        new ConnectionProvider() {
          @Override
          public Connection acquire(ExecutionContext context) {
            return connection;
          }

          @Override
          public void release(Connection released, ExecutionContext context) throws SQLException {
            releases.incrementAndGet();
            throw releaseFailure;
          }
        };
    MutationOperations operations =
        MutationRuntime.create(
            EntityRuntimeRegistry.of(List.of(runtimeModel())),
            TestDialect.INSTANCE,
            new JdbcExecutor(provider));

    MutationException thrown =
        assertThrows(
            MutationException.class,
            () -> operations.insert(PET, new Pet(1L, SENSITIVE_NAME)));

    String message = Objects.requireNonNull(thrown.getMessage(), "failure message");
    assertTrue(message.startsWith("failed to insert entity 'Pet'; JDBC insert failed"));
    assertTrue(message.contains("phase=execution"));
    assertTrue(message.contains("dialect=test"));
    assertTrue(message.contains("sqlFingerprint="));
    assertTrue(message.contains("sqlState=42000"));
    assertTrue(message.contains("vendorCode=71"));
    assertFalse(message.contains("INSERT INTO"));
    assertFalse(message.contains(SENSITIVE_NAME));
    assertSame(executionFailure, thrown.getCause());
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(releaseFailure, thrown.getSuppressed()[0]);
    assertEquals(1, statementCloses.get());
    assertEquals(1, releases.get());
  }

  private static EntityRuntimeModel<Pet> runtimeModel() {
    EntityMutationBinders<Pet> binders =
        new EntityMutationBinders<>(
            (statement, firstIndex, value, context) -> firstIndex + 2,
            (statement, firstIndex, value, context) -> firstIndex + 2,
            (statement, firstIndex, value, context) -> firstIndex + 2,
            null);
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(1L, "Mimi"),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)),
        binders);
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

  private record Pet(Long id, String name) {}

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

    @Override
    public String id() {
      return "test";
    }

    @Override
    public IdentifierRules identifierRules() {
      return StandardIdentifierRules.INSTANCE;
    }

    @Override
    public DialectCapabilities capabilities() {
      return capabilities;
    }

    @Override
    public SqlRenderer renderer() {
      return renderer;
    }
  }
}
