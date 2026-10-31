package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.skis.metadata.*;
import io.skis.query.*;
import io.skis.sql.ast.Nullability;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ComposableQueryModelTest {
  private record Item(Long id, @Nullable String name) {}
  private static final PropertyMeta<Item, Long> ID = new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Item, String> NAME = new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", true));
  private static final EntityMeta<Item> META = EntityMeta.simple(Item.class, TableMeta.of("item"), List.of(ID, NAME), new PrimaryKeyMeta<>(List.of(ID)), false);
  private static final class Table extends QueryTable<Item> {
    Table(String alias) { super(META, alias); }
    @Override public Table as(String alias) { return new Table(alias); }
    NonNullSelectable<Long> id() { return nonNullQueryColumn(ID); }
    Selectable<String> name() { return queryColumn(NAME); }
  }
  @Test void independentTerminalsBuildRequestsAndDerivativesKeepTheirOwnValues() {
    Table table = new Table("i");
    var original = Sql.select(table.id()).from(table).where(table.id().eq(1L));
    var derived = original.and(table.id().lt(9L));
    assertInstanceOf(QueryRequest.class, original.fetchList());
    assertInstanceOf(QueryRequest.class, derived.fetchOne());
    var first = QueryModels.description(original);
    var second = QueryModels.description(derived);
    var firstReferences = first.state.structure().validationStructure().parameterReferences();
    var secondReferences = second.state.structure().validationStructure().parameterReferences();
    first.parameters.validateFor(firstReferences); second.parameters.validateFor(secondReferences);
    assertEquals(List.of(1L), first.parameters.valuesFor(firstReferences));
    assertEquals(List.of(1L, 9L), second.parameters.valuesFor(secondReferences));
    assertNotSame(original, derived);
  }
  @Test void mutableValueSnapshotsAreSharedAcrossSourceAndRequestDerivatives() {
    Table table = new Table("i"); byte[] bytes = {1, 2};
    var selection = Sql.value(bytes);
    var query = Sql.select(selection).from(table);
    bytes[0] = 99;
    var model = QueryModels.description(query);
    var parameters = model.state.structure().validationStructure().parameterReferences();
    var first = model.parameters.valuesFor(parameters).getFirst();
    var derivative = QueryModels.description(query.where(table.id().eq(1L)));
    var second = derivative.parameters.valuesFor(parameters).getFirst();
    assertArrayEquals(new byte[] {1, 2}, (byte[]) first);
    assertSame(first, second);
    assertInstanceOf(QueryRequest.class, query.fetchList());
  }
  @Test void fixedRowsKeepNullableReadViewsAndDerivedOccurrenceOwnership() {
    Table root = new Table("root"), joined = new Table("joined");
    var joinedId = joined.id().as("joined_id");
    SelectQuery<Row2<Long, @Nullable Long>> query = Sql.select(root.id(), Sql.nullable(joinedId)).from(root)
        .leftJoin(joined).on(root.id().eq(joined.id()));
    var source = query.as("source");
    assertEquals(Nullability.NON_NULL, source.column(root.id()).nullability());
    assertEquals(Nullability.NULLABLE, source.columnNullable(joinedId).nullability());
    assertThrows(RuntimeException.class, () -> source.column(joinedId));
    assertThrows(RuntimeException.class, () -> source.column(new Table("other").id()));
  }
  @Test void computationAliasesAreDeclaredOnceAndColumnsAreInferredFromTheSelection() {
    Table table = new Table("i");
    var total = Sql.add(table.id(), 1L).as("total");
    var source = Sql.select(table.id(), total).from(table).as("computed");
    assertEquals(Long.class, source.column(total).javaType());
    assertEquals(Long.class, source.column(table.id()).javaType());
    assertThrows(RuntimeException.class, () -> Sql.select(table.id(), table.id()).from(table).as("ambiguous"));
  }
  @Test void longImmutableParameterChainsDoNotRecurseOrRecopyOwnedValues() {
    QueryParameters values = QueryParameters.empty();
    List<QueryParameter<?>> parameters = new ArrayList<>();
    for (long index = 0; index < 20_000; index++) {
      var parameter = new DefaultQueryParameter<>(Long.class, Nullability.NON_NULL);
      parameters.add(parameter); values = values.merge(QueryParameters.of(parameter, index));
    }
    var arguments = values.valuesFor(parameters);
    assertEquals(20_000, arguments.size()); assertEquals(0L, arguments.getFirst()); assertEquals(19_999L, arguments.getLast());
  }
  @Test void separatelyNamedOccurrencesOfOneExpressionRemainDistinct() {
    Table table = new Table("i");
    var expression = Sql.add(table.id(), 1L);
    var first = expression.as("first_value");
    var second = expression.as("second_value");
    var source = Sql.select(first, second).from(table).as("named");
    assertNotSame(source.column(first), source.column(second));
    assertThrows(RuntimeException.class, () -> source.column(expression.as("first_value")));
  }
  @Test void replacingSameTypedOrderingKeepsTheReplacementParameterIdentity() {
    Table table = new Table("i");
    var original = Sql.select(table.id()).from(table).orderBy(Sql.add(table.id(), 1L).asc());
    var replacement = original.orderBy(Sql.add(table.id(), 2L).asc());
    var model = QueryModels.description(replacement);
    var references = model.state.structure().validationStructure().parameterReferences();
    model.parameters.validateFor(references);
    assertNotSame(QueryModels.description(original).state, model.state);
    assertEquals(List.of(2L), model.parameters.valuesFor(references));
  }
  @Test void derivedScalarLookupRejectsAnIndependentSameTypedParameter() {
    Table outer = new Table("o"), inner = new Table("i");
    var selected = Sql.scalar(Sql.select(inner.id()).from(inner).where(inner.id().eq(1L)));
    var other = Sql.scalar(Sql.select(inner.id()).from(inner).where(inner.id().eq(2L)));
    var source = Sql.derived(Sql.select(selected).from(outer), "chosen",
        Sql.output(selected, "chosen_id"));
    assertNotNull(source.column(selected));
    var failure = assertThrows(io.skis.core.QueryValidationException.class, () -> source.column(other));
    assertEquals(io.skis.core.SkisErrorCode.QUERY_DERIVED_SHAPE, failure.code());
    assertNotEquals(SelectableSupport.expressionIdentity(selected),
        SelectableSupport.expressionIdentity(other));
  }
  @Test void publicTemplateAndDescriptionEntrypointsAreAbsent() {
    assertFalse(Arrays.stream(Sql.class.getMethods()).anyMatch(method -> method.getName().equals("parameter")));
    assertFalse(Arrays.stream(SelectQuery.class.getMethods()).anyMatch(method -> method.getName().equals("bind")));
    assertFalse(Arrays.stream(QueryOperations.class.getMethods()).anyMatch(method -> method.getName().equals("query")));
    assertThrows(ClassNotFoundException.class, () -> Class.forName("io.skis.query.SelectDescription"));
    assertThrows(ClassNotFoundException.class, () -> Class.forName("io.skis.query.QueryParameter"));
  }
}
