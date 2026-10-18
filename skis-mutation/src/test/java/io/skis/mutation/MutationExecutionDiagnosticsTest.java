package io.skis.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.JdbcPhase;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityMutationBinders;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.mutation.internal.MutationRuntime;
import io.skis.query.QueryTable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
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
  private static final PetTable PET_TABLE = new PetTable();

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

    JdbcExecutionException thrown =
        assertThrows(
            JdbcExecutionException.class,
            () -> operations.insert(PET_TABLE, new Pet(1L, SENSITIVE_NAME)));

    assertEquals(SkisErrorCode.DB_UNCATEGORIZED, thrown.code());
    assertEquals(JdbcPhase.EXECUTE, thrown.phase());
    String message = Objects.requireNonNull(thrown.getMessage(), "failure message");
    assertTrue(message.contains("[SKISX019]"));
    assertTrue(message.contains("operation=mutation"));
    assertTrue(message.contains("phase=execution"));
    assertTrue(message.contains("statementKind=insert"));
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

  @Test
  void propagatesPerStatementOptionsThroughTheMutationFacade() {
    AtomicReference<ExecutionOptions> observed = new AtomicReference<>();
    ConnectionProvider provider =
        new ConnectionProvider() {
          @Override
          public Connection acquire(ExecutionContext context) throws SQLException {
            observed.set(context.executionOptions());
            throw new SQLException("stop before JDBC work", "08001");
          }

          @Override
          public void release(Connection connection, ExecutionContext context) {}
        };
    MutationOperations operations =
        MutationRuntime.create(
            EntityRuntimeRegistry.of(List.of(runtimeModel())),
            TestDialect.INSTANCE,
            new JdbcExecutor(provider));
    ExecutionOptions options = ExecutionOptions.builder().maxRows(1).build();

    assertThrows(
        JdbcExecutionException.class,
        () -> operations.insert(PET_TABLE, new Pet(1L, SENSITIVE_NAME), options));

    assertSame(options, observed.get());
  }

  @Test
  void classifiesGeneratedBinderValueFailuresAsMutationInputFailures() {
    PreparedStatement statement =
        proxy(
            PreparedStatement.class,
            (ignored, method, arguments) -> defaultValue(method.getReturnType()));
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
          public void release(Connection released, ExecutionContext context) {}
        };
    EntityMutationBinders<Pet> binders =
        new EntityMutationBinders<>(
            (prepared, firstIndex, value, context) -> {
              JdbcCodecs.bindLong(prepared, firstIndex, value.id(), context);
              JdbcCodecs.bindString(
                  prepared,
                  firstIndex + 1,
                  JdbcCodecs.requireBindValue(value.name(), firstIndex + 1),
                  context);
              return firstIndex + 2;
            },
            (prepared, firstIndex, value, context) -> firstIndex + 2,
            (prepared, firstIndex, value, context) -> firstIndex + 2,
            null);
    EntityRuntimeModel<Pet> model =
        new EntityRuntimeModel<>(
            PET,
            layout -> (resultSet, context) -> new Pet(1L, "Mimi"),
            List.of(
                new PropertyRuntime<>(ID, JdbcCodecs.LONG),
                new PropertyRuntime<>(NAME, JdbcCodecs.STRING)),
            binders);
    MutationOperations operations =
        MutationRuntime.create(
            EntityRuntimeRegistry.of(List.of(model)),
            TestDialect.INSTANCE,
            new JdbcExecutor(provider));

    MutationException failure =
        assertThrows(
            MutationException.class, () -> operations.insert(PET_TABLE, new Pet(1L, null)));

    assertEquals(SkisErrorCode.MUTATION_REQUIRED_VALUE_NULL, failure.code());
    assertNull(failure.getCause());
  }

  @Test
  void optimisticLockExplicitDiagnosticsRequireExecutionDetail() {
    SkisDiagnostics wrongStage =
        SkisDiagnostics.of(SkisDiagnosticStage.RESULT_DECODING, "optimistic conflict");
    SkisDiagnostics missingDetail =
        SkisDiagnostics.of(SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT.defaultStage());

    assertThrows(IllegalArgumentException.class, () -> OptimisticLockException.of(wrongStage));
    assertThrows(IllegalArgumentException.class, () -> OptimisticLockException.of(missingDetail));
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

  private record Pet(Long id, @Nullable String name) {}

  private static final class PetTable extends QueryTable<Pet> {

    private PetTable() {
      super(PET);
    }

    private PetTable(String alias) {
      super(PET, alias);
    }

    @Override
    public PetTable as(String alias) {
      return new PetTable(alias);
    }
  }

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
