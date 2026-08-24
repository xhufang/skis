package io.skis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SkisPlanReuseTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void openingTransactionsOnlyRebindsThePrecompiledPlanCatalogs() {
    CountingDialect dialect = new CountingDialect();
    SkisExecutor executor =
        SkisExecutorFactory.builder()
            .dataSource(dataSource())
            .dialect(dialect)
            .runtimeRegistry(EntityRuntimeRegistry.of(List.of(runtimeModel())))
            .build();
    int rendersAfterAssembly = dialect.renders.get();

    try (SkisSession ignored = executor.beginTransaction()) {
      assertEquals(rendersAfterAssembly, dialect.renders.get());
    }
    try (SkisSession ignored = executor.beginTransaction()) {
      assertEquals(rendersAfterAssembly, dialect.renders.get());
    }

    assertTrue(rendersAfterAssembly > 0);
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

  private static DataSource dataSource() {
    Connection connection =
        proxy(
            Connection.class,
            (ignored, method, arguments) ->
                method.getName().equals("getAutoCommit")
                    ? true
                    : defaultValue(method.getReturnType()));
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

  private static final class CountingDialect implements Dialect {

    private final AtomicInteger renders = new AtomicInteger();
    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer delegate =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

    @Override
    public String id() {
      return "counting";
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
      return statement -> {
        renders.incrementAndGet();
        return delegate.render(statement);
      };
    }
  }
}
