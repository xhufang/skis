package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.spi.EntityMutationBinders;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MutationParameterBindingTest {

  @Test
  void deleteBindsTheFinalSlotBeforeObservationAndStillBindsWhenObservationStops() throws Exception {
    var binder = new MutationPlanCompiler(TestDialect.INSTANCE, ExecutionListenerDispatcher.NONE)
        .compile(runtimeModel()).delete().parameterBinder();
    Long value = 1000L;
    for (boolean observing : List.of(false, true)) {
      var bindings = new ArrayList<List<Object>>();
      PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
          getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
          (proxy, method, arguments) -> {
            if (!method.getName().equals("setLong")) {
              throw new AssertionError("unexpected JDBC call: " + method.getName());
            }
            bindings.add(List.of(arguments[0], arguments[1]));
            return null;
          });
      var probes = new AtomicInteger();
      var callbacks = new AtomicInteger();
      var observer = new ParameterBindingObserver() {
        @Override public boolean needsMore() {
          probes.incrementAndGet();
          return observing;
        }

        @Override public void bound(int index, Class<?> declaredType,
            @org.jspecify.annotations.Nullable Object boundValue) {
          assertTrue(observing, "observation continued after its budget was exhausted");
          assertEquals(7, index);
          assertSame(Long.class, declaredType);
          assertSame(value, boundValue);
          assertEquals(List.of(List.of(7, value)), bindings, "observation ran before JDBC binding");
          callbacks.incrementAndGet();
        }

        @Override public void unavailable() {
          fail("framework delete binder must expose its bound slot");
        }
      };

      assertEquals(8, binder.bindObserved(statement, 7, value, JdbcWriteContext.EMPTY, observer));
      assertEquals(List.of(List.of(7, value)), bindings, "JDBC binding was skipped or repeated");
      assertEquals(1, probes.get());
      assertEquals(observing ? 1 : 0, callbacks.get());
    }
  }

  private static EntityRuntimeModel<Pet> runtimeModel() {
    var id = new PropertyMeta<Pet, Long>(0, "id", Long.class, ColumnMeta.of("id", false));
    var name = new PropertyMeta<Pet, String>(1, "name", String.class, ColumnMeta.of("pet_name", false));
    var entity = EntityMeta.simple(Pet.class, TableMeta.of("pet"), List.of(id, name),
        new PrimaryKeyMeta<>(List.of(id)), false);
    EntityMutationBinders<Pet> binders = new EntityMutationBinders<>(
        (statement, firstIndex, value, context) -> firstIndex + 2,
        (statement, firstIndex, value, context) -> firstIndex + 2,
        (statement, firstIndex, value, context) -> firstIndex + 2, null,
            (statement, firstIndex, arguments, context) -> firstIndex + 2,
            new io.skis.mapping.spi.EntityIdAccess<Pet>() {
              @Override public Object read(Pet entity) { return entity.id(); }
              @Override public Pet apply(Pet entity, Object id) {
                return new Pet((Long) id, entity.name());
              }
            });
    return new EntityRuntimeModel<>(entity,
        layout -> (resultSet, context) -> new Pet(1L, "Mimi"),
        List.of(new PropertyRuntime<>(id, JdbcCodecs.LONG),
            new PropertyRuntime<>(name, JdbcCodecs.STRING)), binders);
  }

  private record Pet(Long id, String name) {}

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

    @Override public String id() { return "test"; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() { return capabilities; }
    @Override public SqlRenderer renderer() { return renderer; }
  }
}
