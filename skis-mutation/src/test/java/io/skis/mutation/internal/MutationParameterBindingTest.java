package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityMutationBinders;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.QueryTable;
import io.skis.query.internal.MutationQuerySupport;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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

  @Test
  void structuredBindingKeepsPrefixAndTailIndexesWhenObservationStops() throws Exception {
    for (boolean entityInput : List.of(false, true)) {
      var prefixCalls = new AtomicInteger();
      var model = runtimeModel((statement, firstIndex, pet, context) -> {
        prefixCalls.incrementAndGet();
        statement.setLong(firstIndex, pet.id());
        statement.setString(firstIndex + 1, pet.name());
        return firstIndex + 2;
      });
      var operations = (DefaultMutationOperations) MutationRuntime.create(
          EntityRuntimeRegistry.of(List.of(model)), TestDialect.INSTANCE,
          new JdbcExecutor(new ConnectionProvider() {
            @Override public Connection acquire(ExecutionContext context) {
              throw new AssertionError("binder preparation must not acquire a connection");
            }
            @Override public void release(Connection connection, ExecutionContext context) {
              throw new AssertionError("binder preparation owns no connection");
            }
          }));
      var table = new QueryTable<Pet>(model.entity()) {
        @Override public QueryTable<Pet> as(String alias) { throw new UnsupportedOperationException(); }
      };
      var spec = new StructuredWrites.Spec<>(operations, table);
      spec.conflict = true;
      spec.keys = List.of(table.columns().getFirst());
      spec.assignments = List.of(new MutationQuerySupport.Assignment(table.columns().get(1), "tail", null));
      if (entityInput) spec.entity = new Pet(11L, "prefix");
      else {
        spec.columns = List.copyOf(table.columns());
        spec.values = new Object[] {11L, "prefix"};
      }
      var request = new StructuredWrites.Request<>(spec);
      var prepared = request.prepare(false);
      assertSame(prepared, request.prepare(false));
      var plan = operations.dynamicPlans().structured(
          new DynamicMutationPlans.Request<>(), prepared, null, false, null);
      for (int budget : List.of(0, 1, 10)) {
        var bound = new ArrayList<List<Object>>();
        var observed = new ArrayList<Integer>();
        var unavailable = new AtomicInteger();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
            (proxy, method, arguments) -> {
              assertTrue(method.getName().equals("setLong") || method.getName().equals("setString"));
              bound.add(List.of(arguments[0], arguments[1]));
              return null;
            });
        var observer = new ParameterBindingObserver() {
          @Override public boolean needsMore() { return observed.size() < budget; }
          @Override public void bound(int index, Class<?> declaredType, @Nullable Object value) {
            assertEquals(index - 4, bound.size(), "binding precedes observation at its JDBC position");
            assertEquals(index == 5 ? Long.class : String.class, declaredType);
            assertEquals(bound.getLast().get(1), value);
            observed.add(index);
          }
          @Override public void unavailable() { unavailable.incrementAndGet(); }
        };
        int previousPrefixCalls = prefixCalls.get();
        assertEquals(8, plan.statement().parameterBinder().bindObserved(
            statement, 5, prepared.fixedArguments(), JdbcWriteContext.EMPTY, observer));
        var expected = List.<List<Object>>of(List.of(5, 11L), List.of(6, "prefix"), List.of(7, "tail"));
        assertEquals(expected, bound, "an exhausted observer never truncates JDBC binding");
        var observable = entityInput ? List.of(7) : List.of(5, 6, 7);
        assertEquals(observable.subList(0, Math.min(budget, observable.size())), observed);
        assertEquals(entityInput ? 1 : 0, unavailable.get());
        assertEquals(previousPrefixCalls + (entityInput ? 1 : 0), prefixCalls.get(), "opaque prefixes are never replayed");
        bound.clear();
        assertEquals(8, plan.statement().parameterBinder().bind(
            statement, 5, prepared.fixedArguments(), JdbcWriteContext.EMPTY));
        assertEquals(expected, bound, "observed and ordinary paths use the same layout");
      }
    }
  }

  private static EntityRuntimeModel<Pet> runtimeModel() {
    return runtimeModel((statement, firstIndex, value, context) -> firstIndex + 2);
  }

  private static EntityRuntimeModel<Pet> runtimeModel(ParameterBinder<Pet> insertBinder) {
    var id = new PropertyMeta<Pet, Long>(0, "id", Long.class, ColumnMeta.of("id", false));
    var name = new PropertyMeta<Pet, String>(1, "name", String.class, ColumnMeta.of("pet_name", false));
    var entity = EntityMeta.simple(Pet.class, TableMeta.of("pet"), List.of(id, name),
        new PrimaryKeyMeta<>(List.of(id)), false);
    EntityMutationBinders<Pet> binders = new EntityMutationBinders<>(
        insertBinder,
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
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES, DialectFeature.INSERT_ON_CONFLICT);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

    @Override public String id() { return "test"; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() { return capabilities; }
    @Override public SqlRenderer renderer() { return renderer; }
  }
}
