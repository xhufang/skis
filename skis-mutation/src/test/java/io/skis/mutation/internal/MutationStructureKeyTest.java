package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.metadata.*;
import io.skis.sql.ast.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutationStructureKeyTest {
  private static final BooleanPredicate TRUE = new BooleanPredicate(true);
  private static final LiteralExpression<String> NIL = LiteralExpression.nullLiteral(String.class);

  @Test void rowAssignmentsAndMergeActionsKeepTheirOrderAndSourceShapeInCacheKeys() {
    var target = new Table("target");
    var source = new Table("source");
    var query = new SelectStatement(List.of(source.name, source.other), source);
    var row = new RowAssignment(List.of(target.name, target.other), query);
    var reversed = new RowAssignment(List.of(target.other, target.name), query);
    var first = new UpdateStatement(target, List.of(row), TRUE, FromClause.of(source));
    assertEquals(key(first), key(new UpdateStatement(target,
        List.of(new RowAssignment(row.columns(), query)), TRUE, FromClause.of(source))));
    assertNotEquals(key(first), key(new UpdateStatement(target, List.of(reversed), TRUE, FromClause.of(source))));
    assertNotEquals(key(first), key(new UpdateStatement(target, List.of(row), TRUE)));
    var scalar = new UpdateAssignment<>(target.name, NIL);
    var merge = new MergeStatement(target, RelationSource.entity(source), TRUE,
        List.of(MergeAction.update(TRUE, List.of(row, scalar))));
    assertNotEquals(key(merge), key(new MergeStatement(target, RelationSource.entity(source), TRUE,
        List.of(MergeAction.update(TRUE, List.of(scalar, row))))));
    assertNotEquals(key(merge), key(new MergeStatement(target, RelationSource.entity(source), TRUE,
        List.of(MergeAction.update(TRUE, List.of(reversed, scalar))))));
    var delete = new DeleteStatement(target, TRUE, FromClause.of(source));
    assertNotEquals(key(delete), key(new DeleteStatement(target, TRUE)));
    assertEquals(key(delete), key(new DeleteStatement(target, TRUE, FromClause.of(new Table("source")))));
  }

  @Test void insertSourcesAndSelectExecutionMetadataCannotShareIncompatibleKeys() {
    var target = new Table("target");
    var source = new Table("source");
    var plain = new SelectStatement(List.of(source.name), source);
    var insert = InsertStatement.select(target, List.of(target.name), plain);
    assertNotEquals(key(insert), key(new InsertStatement(target, List.of(target.name), List.of(NIL))));
    assertNotEquals(key(new InsertStatement(target, List.of(target.name), List.of(NIL))),
        key(new InsertStatement(target, List.of(target.name), List.of(NIL), List.of(List.of(NIL)), null)));
    var limit = new ParameterSlot<>(0, Integer.class, false);
    var offset = new ParameterSlot<>(1, Long.class, false);
    var order = List.of(new OrderByItem(source.name, OrderDirection.ASC, NullOrder.LAST));
    var paged = new SelectStatement(false, List.of(source.name), List.of(), source, TRUE,
        order, new OffsetLimit(limit, offset));
    assertNotEquals(key(plain), key(paged));
    assertEquals(key(paged), key(new SelectStatement(false, List.of(source.name), List.of(), source,
        TRUE, order, new OffsetLimit(new ParameterSlot<>(0, Integer.class, false),
            new ParameterSlot<>(1, Long.class, false)))));
    assertNotEquals(key(paged), key(new SelectStatement(false, List.of(source.name), List.of(), source,
        TRUE, order, new KeysetSeek(TRUE, limit))));
    var hidden = new SelectStatement(false, List.of(source.name),
        List.of(new HiddenSelection(source.id, Identifier.of("hidden_id"))), source, null, List.of(), null);
    assertNotEquals(key(plain), key(hidden));
    assertNotEquals(key(hidden), key(new SelectStatement(false, List.of(source.name),
        List.of(new HiddenSelection(source.id, Identifier.of("other_id"))), source, null, List.of(), null)));
    assertThrows(IllegalArgumentException.class, () -> key(new StatementAst() {}));
  }

  @Test void derivedAndValuesSourcesRetainAliasesDescriptorsAndTheirNestedQueries() {
    var target = new Table("target");
    var source = new Table("source");
    var reference = new DerivedRelationReference(Identifier.of("input"), List.of(
        new DerivedOutputColumn(Identifier.of("name"), String.class, SqlType.VARCHAR, Nullability.NULLABLE)));
    var query = new SelectStatement(List.of(source.name), source);
    var base = new Table("base");
    var assignment = new UpdateAssignment<>(target.name, new DerivedColumnExpression<String>(reference, 0));
    var derived = new UpdateStatement(target, List.of(assignment), TRUE,
        cross(base, new DerivedRelationSource(query, reference)));
    var parameters = new UpdateStatement(target, List.of(assignment), TRUE,
        cross(base, new ParameterRelationSource(List.of(new ParameterSlot<>(0, String.class, true)), reference)));
    assertNotEquals(key(derived), key(parameters));
    assertEquals(key(parameters), key(new UpdateStatement(target, List.of(assignment), TRUE,
        cross(base, new ParameterRelationSource(List.of(new ParameterSlot<>(0, String.class, true)), reference)))));
    var filtered = new SelectStatement(List.of(source.name), source, TRUE);
    assertNotEquals(key(derived), key(new UpdateStatement(target, List.of(assignment), TRUE,
        cross(base, new DerivedRelationSource(filtered, reference)))));
  }

  private static List<Object> key(StatementAst statement) { return MutationStructureKey.of(statement); }

  private static FromClause cross(Table base, RelationSource source) {
    return new FromClause(base, List.of(new JoinClause(JoinType.CROSS, source, null)));
  }

  private static final class Table extends TableExpression<Object> {
    private static final PropertyMeta<Object, Long> ID = new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    private static final PropertyMeta<Object, String> NAME = new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", true));
    private static final PropertyMeta<Object, String> OTHER = new PropertyMeta<>(2, "other", String.class, ColumnMeta.of("other", true));
    private static final EntityMeta<Object> META = EntityMeta.simple(Object.class, TableMeta.of("item"),
        List.of(ID, NAME, OTHER), new PrimaryKeyMeta<>(List.of(ID)), false);
    final ColumnExpression<Object, Long> id = column(ID);
    final ColumnExpression<Object, String> name = column(NAME);
    final ColumnExpression<Object, String> other = column(OTHER);
    Table(String alias) { super(META, Identifier.of(alias)); }
    @Override public Table as(Identifier alias) { return new Table(alias.value()); }
  }
}
