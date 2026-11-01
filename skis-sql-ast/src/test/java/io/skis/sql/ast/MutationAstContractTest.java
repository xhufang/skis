package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.metadata.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutationAstContractTest {
  private static final BooleanPredicate TRUE = new BooleanPredicate(true);
  private static final LiteralExpression<String> NULL_TEXT = LiteralExpression.nullLiteral(String.class);

  @Test void mergeActionsRequireExactlyTheirOwnCompletePayload() {
    var table = new Table("target");
    var assignment = new UpdateAssignment<>(table.name, NULL_TEXT);
    for (var kind : MergeAction.Kind.values()) {
      if (kind == MergeAction.Kind.UPDATE) {
        assertThrows(IllegalArgumentException.class, () -> MergeAction.update(TRUE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MergeAction(kind, TRUE,
            List.of(assignment), List.of(table.name), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MergeAction(kind, TRUE,
            List.of(assignment), List.of(), List.of(NULL_TEXT)));
      } else if (kind == MergeAction.Kind.DELETE) {
        assertThrows(IllegalArgumentException.class, () -> new MergeAction(kind, TRUE,
            List.of(assignment), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MergeAction(kind, TRUE,
            List.of(), List.of(table.name), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MergeAction(kind, TRUE,
            List.of(), List.of(), List.of(NULL_TEXT)));
      } else {
        assertThrows(IllegalArgumentException.class, () -> new MergeAction(kind, TRUE,
            List.of(assignment), List.of(table.name), List.of(NULL_TEXT)));
        assertThrows(IllegalArgumentException.class, () -> MergeAction.insert(TRUE, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> MergeAction.insert(TRUE, List.of(table.name), List.of()));
      }
    }
    var assignments = new ArrayList<UpdateAssignment<?>>(List.of(assignment));
    var update = MergeAction.update(TRUE, assignments);
    assignments.clear();
    assertEquals(List.of(assignment), update.assignments());
    assertThrows(UnsupportedOperationException.class, () -> update.assignments().clear());
  }

  @Test void keyAndUsingFormsCannotMixTheirInputs() {
    var table = new Table("target");
    var source = RelationSource.entity(new Table("source"));
    var action = MergeAction.delete(TRUE);
    List<ColumnExpression<?, ?>> columns = List.of(table.id);
    List<SqlExpression<?>> values = List.of(LiteralExpression.one(Long.class));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, null, TRUE, List.of(), columns, values, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, null, TRUE, List.of(action), columns, values, columns));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, null, TRUE, List.of(), List.of(), List.of(), columns));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, null, TRUE, List.of(), columns, List.of(), columns));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, source, TRUE, List.of(action), List.of(), List.of(), columns));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, source, TRUE, List.of(action), columns, List.of(), List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, source, TRUE, List.of(action), List.of(), values, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new MergeStatement(table, source, TRUE, List.of()));
    var key = new MergeStatement(table, null, TRUE, List.of(), columns, values, columns);
    assertTrue(key.keyForm());
    assertEquals(0, key.scope().joins().size());
    assertSame(table, key.scope().root().entityTable().orElseThrow());
    assertFalse(key.hasDelete());
    assertFalse(key.onlyDelete());
    var delete = new MergeStatement(table, source, TRUE, List.of(action));
    assertFalse(delete.keyForm());
    assertEquals(1, delete.scope().joins().size());
    assertTrue(delete.hasDelete());
    assertTrue(delete.onlyDelete());
    var mixed = new MergeStatement(table, source, TRUE, List.of(action,
        MergeAction.update(TRUE, List.of(new UpdateAssignment<>(table.name, NULL_TEXT)))));
    assertTrue(mixed.hasDelete());
    assertFalse(mixed.onlyDelete());
    assertThrows(IllegalArgumentException.class, () -> new ReturningStatement(mixed, List.of(table.id)));
  }

  @Test void returningPreservesBeforeAndAfterImagesAndRejectsWrongForms() {
    var table = new Table("target");
    var update = new UpdateStatement(table, List.of(new UpdateAssignment<>(table.name, NULL_TEXT)), TRUE);
    var delete = new DeleteStatement(table, TRUE);
    assertSame(table, new ReturningStatement(update, List.of(table.id), ReturningStatement.Form.FINAL_TABLE).target());
    assertSame(table, new ReturningStatement(delete, List.of(table.id), ReturningStatement.Form.OLD_TABLE).target());
    assertThrows(IllegalArgumentException.class,
        () -> new ReturningStatement(update, List.of(table.id), ReturningStatement.Form.OLD_TABLE));
    assertThrows(IllegalArgumentException.class,
        () -> new ReturningStatement(delete, List.of(table.id), ReturningStatement.Form.FINAL_TABLE));
    assertThrows(IllegalArgumentException.class,
        () -> new ReturningStatement(update, List.of(table.id), ReturningStatement.Form.NATIVE, Identifier.of("wrong")));
    assertThrows(IllegalArgumentException.class,
        () -> new ReturningStatement(update, List.of(table.id), ReturningStatement.Form.FINAL_TABLE, Identifier.of("wrong")));
    assertThrows(IllegalArgumentException.class, () -> new ReturningStatement(update, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new ReturningStatement(new SelectStatement(List.of(table.id), table), List.of(table.id)));
    var mergeDelete = new MergeStatement(table, RelationSource.entity(new Table("source")), TRUE,
        List.of(MergeAction.delete(TRUE)));
    assertSame(table, new ReturningStatement(mergeDelete, List.of(table.id), ReturningStatement.Form.OLD_TABLE).target());
    assertThrows(IllegalArgumentException.class,
        () -> new ReturningStatement(mergeDelete, List.of(table.id), ReturningStatement.Form.FINAL_TABLE));
  }

  @Test void rowAssignmentsAndInsertSelectEnforceArityAndExclusiveInputForms() {
    var table = new Table("target");
    var source = new Table("source");
    var pair = new SelectStatement(List.of(source.name, source.other), source);
    assertThrows(IllegalArgumentException.class, () -> new RowAssignment(List.of(table.name), pair));
    assertThrows(IllegalArgumentException.class, () -> new RowAssignment(List.of(table.name, table.other),
        new SelectStatement(List.of(source.name), source)));
    var hidden = new SelectStatement(false, List.of(source.name, source.other),
        List.of(new HiddenSelection(source.id, Identifier.of("hidden_id"))), source, null, List.of(), null);
    assertThrows(IllegalArgumentException.class,
        () -> new RowAssignment(List.of(table.name, table.other), hidden));
    var columns = new ArrayList<ColumnExpression<?, ?>>(List.of(table.name, table.other));
    var assignment = new RowAssignment(columns, pair);
    columns.clear();
    assertEquals(2, assignment.columns().size());
    assertThrows(UnsupportedOperationException.class, () -> assignment.columns().clear());
    var query = new SelectStatement(List.of(source.name), source);
    assertThrows(IllegalArgumentException.class, () -> new InsertStatement(table, List.of(table.name),
        List.of(NULL_TEXT), List.of(), query));
    assertThrows(IllegalArgumentException.class, () -> new InsertStatement(table, List.of(table.name),
        List.of(), List.of(List.of(NULL_TEXT)), query));
    assertThrows(IllegalArgumentException.class,
        () -> InsertStatement.select(table, List.of(), query));
    assertThrows(IllegalArgumentException.class,
        () -> new InsertStatement(table, List.of(table.name), List.of()));
    assertSame(query, InsertStatement.select(table, List.of(table.name), query).query());
  }

  @Test void updateFromRejectsNullExtendedValuesForRequiredTargets() {
    var target = new Table("target");
    var source = new Table("source");
    var optional = new Table("optional");
    var from = new FromClause(source, List.of(new JoinClause(JoinType.LEFT, optional, TRUE)));
    assertThrows(IllegalArgumentException.class, () -> new UpdateStatement(target,
        List.of(new UpdateAssignment<>(target.id, optional.id)), TRUE, from));
    assertDoesNotThrow(() -> new UpdateStatement(target,
        List.of(new UpdateAssignment<>(target.id, source.id)), TRUE, from));
    assertDoesNotThrow(() -> new UpdateStatement(target,
        List.of(new UpdateAssignment<>(target.name, optional.name)), TRUE, from));
  }

  @Test void requiredRowTargetsNeedAnAggregateQueryThatCannotLoseItsOnlyRow() {
    var target = new Table("target");
    var source = new Table("source");
    List<ColumnExpression<?, ?>> columns = List.of(target.id, target.name);
    List<SqlExpression<?>> outputs = List.of(AggregateExpression.countAll(), NULL_TEXT);
    var guaranteed = new SelectStatement(outputs, source);
    assertDoesNotThrow(() -> new UpdateStatement(target,
        List.of(new RowAssignment(columns, guaranteed)), TRUE));

    var grouped = new SelectStatement(false, outputs, List.of(), source, null,
        List.of(source.name), null, List.of(), null);
    var filtered = new SelectStatement(false, outputs, List.of(), source, null,
        List.of(), new BooleanPredicate(false), List.of(), null);
    var paged = new SelectStatement(false, outputs, List.of(), source, null, List.of(), null,
        List.of(), new OffsetLimit(new ParameterSlot<>(0, Integer.class, false),
            new ParameterSlot<>(1, Long.class, false)));
    var ordinary = new SelectStatement(List.of(source.id, source.name), source);
    for (var query : List.of(grouped, filtered, paged, ordinary))
      assertThrows(IllegalArgumentException.class, () -> new UpdateStatement(target,
          List.of(new RowAssignment(columns, query)), TRUE));
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
