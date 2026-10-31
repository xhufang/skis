package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.MutationException;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.*;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.*;
import io.skis.metadata.*;
import io.skis.query.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class WriteCodecsTest {
  private record Item(Long id, @Nullable String plain, @Nullable String coded) {}

  private static final PropertyMeta<Item, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Item, String> PLAIN =
      new PropertyMeta<>(1, "plain", String.class, ColumnMeta.of("plain", true));
  private static final PropertyMeta<Item, String> CODED =
      new PropertyMeta<>(2, "coded", String.class, ColumnMeta.of("coded", true));
  private static final EntityMeta<Item> META = EntityMeta.simple(
      Item.class, TableMeta.of("codec_item"), List.of(ID, PLAIN, CODED),
      new PrimaryKeyMeta<>(List.of(ID)), false);
  private static final JdbcTypeCodec<String> CODEC = new JdbcTypeCodec<>() {
    @Override public @Nullable String read(ResultSet rs, int index, JdbcReadContext context)
        throws SQLException {
      String value = JdbcCodecs.STRING.read(rs, index, context);
      return value == null ? null : value.substring("encoded:".length());
    }

    @Override public void bind(PreparedStatement statement, int index, @Nullable String value,
        JdbcWriteContext context) throws SQLException {
      JdbcCodecs.STRING.bind(statement, index, value == null ? null : "encoded:" + value, context);
    }
  };

  private static final class Table extends QueryTable<Item> {
    Table(String alias) { super(META, alias); }
    @Override public Table as(String alias) { return new Table(alias); }
    NonNullSelectable<Long> id() { return nonNullQueryColumn(ID); }
    Selectable<String> plain() { return queryColumn(PLAIN); }
    Selectable<String> coded() { return queryColumn(CODED); }
  }

  @Test void insertSelectRejectsDifferentCodecsThroughMultipleDerivedLayers() {
    var operations = operations();
    var target = new Table("target");
    var source = source(new Table("source"));
    var query = Sql.select(source.id(), source.coded()).from(source.relation());
    var request = operations.insertInto(target).columns(target.id(), target.plain()).select(query);
    assertMismatch(request::write);
    assertMismatch(() -> request.returning().write());
  }

  @Test void matchingDerivedCodecsKeepInsertSelectAndRowAssignmentsValid() {
    var operations = operations();
    var target = new Table("target");
    var source = source(new Table("source"));
    var query = Sql.select(source.id(), source.coded()).from(source.relation());
    var insert = operations.insertInto(target).columns(target.id(), target.coded()).select(query);
    assertDoesNotThrow(() -> ((MutationRequests.WriteRequest<?>) insert).prepare());
    var update = operations.update(target).set(Sql.row(target.plain(), target.coded()),
        Sql.select(source.plain(), source.coded()).from(source.relation()))
        .where(target.id().eq(1L));
    assertDoesNotThrow(() -> ((MutationRequests.WriteRequest<?>) update).prepare());
  }

  @Test void queryParametersAndComputationsCannotBypassTheTargetCodec() {
    var operations = operations();
    var target = new Table("target");
    var source = new Table("source");
    for (Selectable<String> expression : List.of(
        Sql.value("plain"), Sql.concat(source.plain(), Sql.value("suffix")))) {
      var query = Sql.select(source.id(), expression).from(source);
      var insert = operations.insertInto(target).columns(target.id(), target.coded()).select(query);
      assertMismatch(insert::write);
      assertMismatch(() -> insert.returning().write());
      var row = Sql.select(source.plain(), expression).from(source);
      var update = operations.update(target).set(Sql.row(target.plain(), target.coded()), row)
          .where(target.id().eq(1L));
      assertMismatch(update::write);
      assertMismatch(() -> update.returning().write());
      var merge = operations.mergeInto(target).using(source).on(target.id().eq(source.id()))
          .whenMatched().thenUpdate().set(Sql.row(target.plain(), target.coded()), row);
      assertMismatch(merge::write);
      assertMismatch(() -> merge.returning().write());
    }
  }

  @Test void computedDerivedOutputsAndScalarQueriesKeepTheirCodecProvenance() {
    var operations = operations();
    var target = new Table("target");
    var source = new Table("source");
    var value = Sql.concat(source.plain(), Sql.value("suffix")).as("computed");
    var derived = Sql.select(source.id(), value).from(source).as("computed_source");
    var insert = operations.insertInto(target).columns(target.id(), target.coded())
        .select(Sql.select(derived.column(source.id()), derived.column(value)).from(derived));
    assertMismatch(insert::write);
    var inner = new Table("scalar_source");
    var scalar = Sql.scalar(Sql.select(inner.coded()).from(inner));
    var valid = operations.insertInto(target).columns(target.id(), target.coded())
        .select(Sql.select(source.id(), scalar).from(source));
    assertDoesNotThrow(() -> ((MutationRequests.WriteRequest<?>) valid).prepare());
    var invalid = operations.insertInto(target).columns(target.id(), target.plain())
        .select(Sql.select(source.id(), scalar).from(source));
    assertMismatch(invalid::write);
  }

  @Test void builtInComputationOutputsRemainValidForBuiltInTargets() {
    var operations = operations();
    var target = new Table("target");
    var source = new Table("source");
    var computed = Sql.concat(source.plain(), Sql.value("suffix"));
    var insert = operations.insertInto(target).columns(target.id(), target.plain())
        .select(Sql.select(source.id(), computed).from(source));
    assertDoesNotThrow(() -> ((MutationRequests.WriteRequest<?>) insert).prepare());
    var update = operations.update(target).set(Sql.row(target.plain(), target.coded()),
        Sql.select(computed, source.coded()).from(source)).where(target.id().eq(1L));
    assertDoesNotThrow(() -> ((MutationRequests.WriteRequest<?>) update).prepare());
  }

  @Test void computedOutputsCannotTreatConvertedColumnsAsBuiltInValues() {
    var operations = operations();
    var target = new Table("target");
    var source = new Table("source");
    var insert = operations.insertInto(target).columns(target.id(), target.plain())
        .select(Sql.select(source.id(), Sql.concat(source.coded(), Sql.value("suffix")))
            .from(source));
    var failure = assertThrows(QueryValidationException.class, insert::write);
    assertEquals(SkisErrorCode.QUERY_TYPE_MISMATCH, failure.code());
  }

  @Test void correlatedValuesOutputsUseTheirPreparedParameterCodecs() {
    var operations = operations();
    var target = new Table("target");
    var inner = new Table("inner_source");
    var plain = Sql.output(target.plain(), "source_plain");
    var coded = Sql.output(target.coded(), "source_coded");
    var values = Sql.values(row -> row.value(plain, "plain").value(coded, "coded"));
    var row = Sql.select(values.column(plain), values.column(coded)).from(inner)
        .where(inner.id().eq(target.id()));
    var valid = operations.mergeInto(target).using(values).on(target.id().eq(1L))
        .whenMatched().thenUpdate().set(Sql.row(target.plain(), target.coded()), row);
    assertDoesNotThrow(() -> ((MutationRequests.WriteRequest<?>) valid).prepare());
    var swapped = Sql.select(values.column(coded), values.column(plain)).from(inner)
        .where(inner.id().eq(target.id()));
    var invalid = operations.mergeInto(target).using(values).on(target.id().eq(1L))
        .whenMatched().thenUpdate().set(Sql.row(target.plain(), target.coded()), swapped);
    assertMismatch(invalid::write);
  }

  @Test void rowAssignmentsRejectDifferentCodecsThroughDerivedSelections() {
    var operations = operations();
    var target = new Table("target");
    var source = source(new Table("source"));
    var query = Sql.select(source.coded(), source.plain()).from(source.relation());
    var update = operations.update(target).set(Sql.row(target.plain(), target.coded()), query)
        .where(target.id().eq(1L));
    assertMismatch(update::write);
    assertMismatch(() -> update.returning().write());
    var merged = operations.mergeInto(target).using(new Table("merge_source"))
        .on(target.id().eq(1L)).whenMatched().thenUpdate()
        .set(Sql.row(target.plain(), target.coded()), query);
    assertMismatch(merged::write);
    assertMismatch(() -> merged.returning().write());
  }

  @Test void correlatedRowAssignmentsResolveDerivedOutputsInTheirOuterSources() {
    var operations = operations();
    var target = new Table("target");
    var source = source(new Table("source"));
    var inner = new Table("inner_source");
    var query = Sql.select(source.coded(), source.plain()).from(inner)
        .where(inner.id().eq(target.id()));
    var update = operations.update(target).from(source.relation())
        .set(Sql.row(target.plain(), target.coded()), query).where(target.id().eq(1L));
    assertMismatch(update::write);
    assertMismatch(() -> update.returning().write());
    var valid = operations.update(target).from(source.relation())
        .set(Sql.row(target.plain(), target.coded()),
            Sql.select(source.plain(), source.coded()).from(inner)
                .where(inner.id().eq(target.id())))
        .where(target.id().eq(1L));
    assertDoesNotThrow(() -> ((MutationRequests.WriteRequest<?>) valid).prepare());
  }

  private record Source(DerivedRelation relation, NonNullSelectable<Long> id,
      Selectable<String> plain, Selectable<String> coded) {}

  private static Source source(Table table) {
    var first = Sql.select(table.id(), table.plain(), table.coded()).from(table).as("codec_one");
    var id = first.column(table.id());
    var plain = first.column(table.plain());
    var coded = first.column(table.coded());
    var second = Sql.select(id, plain, coded).from(first).as("codec_two");
    return new Source(second, second.column(id), second.column(plain), second.column(coded));
  }

  private static void assertMismatch(Executable action) {
    var failure = assertThrows(MutationException.class, action);
    assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE, failure.code());
    var facts = Objects.requireNonNull(failure.diagnostics().facts());
    assertTrue(Objects.requireNonNull(facts.detail()).contains("different JDBC codecs"));
  }

  private static DefaultMutationOperations operations() {
    ParameterBinder<Item> binder = (statement, first, value, context) -> first + 3;
    var binders = new EntityMutationBinders<>(binder, binder, binder, null,
        (statement, first, arguments, context) -> first + 3, new EntityIdAccess<Item>() {
          @Override public Object read(Item entity) { return entity.id(); }
          @Override public Item apply(Item entity, Object id) {
            return new Item((Long) id, entity.plain(), entity.coded());
          }
        });
    var model = new EntityRuntimeModel<>(META,
        layout -> (resultSet, context) -> new Item(1L, null, null),
        List.of(new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(PLAIN, JdbcCodecs.STRING), new PropertyRuntime<>(CODED, CODEC)),
        binders);
    return DefaultMutationOperations.of(EntityRuntimeRegistry.of(List.of(model)),
        TestDialect.INSTANCE, ExecutionListenerDispatcher.NONE, new JdbcExecutor(new ConnectionProvider() {
          @Override public Connection acquire(ExecutionContext context) {
            throw new AssertionError("Codec validation must finish before JDBC acquisition");
          }
          @Override public void release(Connection connection, ExecutionContext context) {
            throw new AssertionError("Codec preparation owns no connection");
          }
        }));
  }

  private enum TestDialect implements Dialect {
    INSTANCE;
    @Override public String id() { return "test"; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() {
      return DialectCapabilities.of(DialectFeature.INSERT_SELECT,
          DialectFeature.ROW_SUBQUERY_ASSIGNMENT, DialectFeature.MERGE_USING);
    }
    @Override public SqlRenderer renderer() { return new StandardSqlRenderer(this); }
  }
}
