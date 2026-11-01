package io.skis.dialect.spi;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.metadata.*;
import io.skis.sql.ast.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoweringCorrespondenceTest {
  private static final BooleanPredicate TRUE = new BooleanPredicate(true);
  private static final LiteralExpression<String> NIL = LiteralExpression.nullLiteral(String.class);

  @Test void mutationsCannotChangeTargetsColumnsOrSourceKinds() {
    var target = new Table("target");
    var other = new Table("other");
    var insert = new InsertStatement(target, List.of(target.name), List.of(NIL));
    reject(insert, new InsertStatement(other, List.of(other.name), List.of(NIL)));
    reject(insert, new InsertStatement(target, List.of(target.other), List.of(NIL)));
    reject(insert, new InsertStatement(target, List.of(target.name), List.of(NIL), List.of(List.of(NIL)), null));
    reject(insert, InsertStatement.select(target, List.of(target.name),
        new SelectStatement(List.of(other.name), other)));
    var selected = InsertStatement.select(target, List.of(target.name),
        new SelectStatement(List.of(other.name), other, TRUE));
    reject(selected, InsertStatement.select(target, List.of(target.name),
        new SelectStatement(List.of(other.name), other, new BooleanPredicate(false))));
    assertDoesNotThrow(() -> validate(selected, InsertStatement.select(target, List.of(target.name),
        new SelectStatement(List.of(other.name), other, new BooleanPredicate(true)))));
    var update = update(target, target.name);
    reject(update, update(other, other.name));
    reject(update, update(target, target.other));
    reject(update, new UpdateStatement(target, List.of(new UpdateAssignment<>(target.name, NIL),
        new UpdateAssignment<>(target.other, NIL)), TRUE));
    reject(update, new UpdateStatement(target, update.assignments(), TRUE, FromClause.of(other)));
    reject(new UpdateStatement(target, update.assignments(), TRUE, FromClause.of(other)),
        new UpdateStatement(target, update.assignments(), TRUE, FromClause.of(new Table("replacement"))));
    var delete = new DeleteStatement(target, TRUE);
    reject(delete, new DeleteStatement(other, TRUE));
    reject(delete, new DeleteStatement(target, TRUE, FromClause.of(other)));
    reject(new DeleteStatement(target, TRUE, FromClause.of(other)),
        new DeleteStatement(target, TRUE, FromClause.of(new Table("replacement"))));
    reject(delete, new DeleteStatement(target, new BooleanPredicate(false)));
    reject(delete, update);
    assertDoesNotThrow(() -> validate(update, update(target, target.name)));
    assertDoesNotThrow(() -> validate(delete, new DeleteStatement(target, new BooleanPredicate(true))));
  }

  @Test void rowAssignmentsKeepTheirTargetOrderAndTheirSingleQuery() {
    var target = new Table("target");
    var source = new Table("source");
    var query = new SelectStatement(List.of(source.name, source.other), source);
    var row = new RowAssignment(List.of(target.name, target.other), query);
    var reordered = new RowAssignment(List.of(target.other, target.name), query);
    var changedQuery = new RowAssignment(row.columns(),
        new SelectStatement(List.of(source.other, source.name), source));
    var original = new UpdateStatement(target, List.of(row), TRUE);
    reject(original, update(target, target.name));
    reject(update(target, target.name), original);
    reject(original, new UpdateStatement(target, List.of(reordered), TRUE));
    reject(original, new UpdateStatement(target, List.of(changedQuery), TRUE));
    var merge = merge(target, source, MergeAction.update(TRUE, List.of(row)));
    reject(merge, merge(target, source, MergeAction.update(TRUE, List.of(reordered))));
    reject(merge, merge(target, source, MergeAction.update(TRUE,
        List.of(new UpdateAssignment<>(target.name, NIL)))));
    reject(merge(target, source, MergeAction.update(TRUE,
        List.of(new UpdateAssignment<>(target.name, NIL)))), merge);
    assertDoesNotThrow(() -> validate(original,
        new UpdateStatement(target, List.of(new RowAssignment(row.columns(), query)), TRUE)));
    assertDoesNotThrow(() -> validate(merge, merge(target, source,
        MergeAction.update(TRUE, List.of(new RowAssignment(row.columns(), query))))));
  }

  @Test void mergeAndConflictActionsRetainTheirOrderKeysAndColumns() {
    var target = new Table("target");
    var source = new Table("source");
    var update = MergeAction.update(TRUE, List.of(new UpdateAssignment<>(target.name, NIL)));
    var original = merge(target, source, update);
    var other = new Table("other");
    reject(original, merge(other, source,
        MergeAction.update(TRUE, List.of(new UpdateAssignment<>(other.name, NIL)))));
    reject(original, merge(target, new Table("replacement"), update));
    reject(original, merge(target, source, MergeAction.delete(TRUE)));
    reject(original, new MergeStatement(target, RelationSource.entity(source), TRUE,
        List.of(update, MergeAction.delete(TRUE))));
    reject(original, merge(target, source, MergeAction.update(TRUE,
        List.of(new UpdateAssignment<>(target.other, NIL)))));
    var insertAction = MergeAction.insert(TRUE, List.of(target.name, target.other), List.of(NIL, NIL));
    reject(merge(target, source, insertAction), merge(target, source,
        MergeAction.insert(TRUE, List.of(target.other, target.name), List.of(NIL, NIL))));
    var key = new MergeStatement(target, null, TRUE, List.of(), List.of(target.id, target.name),
        List.of(LiteralExpression.one(Long.class), NIL), List.of(target.id));
    reject(key, new MergeStatement(target, null, TRUE, List.of(), key.insertColumns(),
        key.insertValues(), List.of(target.name)));
    reject(key, new MergeStatement(target, null, TRUE, List.of(), List.of(target.name, target.id),
        List.of(NIL, LiteralExpression.one(Long.class)), key.keys()));
    var insert = new InsertStatement(target, List.of(target.id, target.name),
        List.of(LiteralExpression.one(Long.class), NIL));
    var assignments = List.<UpdateAssignment<?>>of(new UpdateAssignment<>(target.name, NIL));
    var conflict = new ConflictInsertStatement(insert, List.of(target.id), assignments, TRUE, false);
    reject(conflict, new ConflictInsertStatement(insert, List.of(target.id), List.of(), TRUE, true));
    reject(conflict, new ConflictInsertStatement(insert, List.of(target.name), assignments, TRUE, false));
    reject(conflict, new ConflictInsertStatement(insert, List.of(target.id),
        List.of(assignments.getFirst(), new UpdateAssignment<>(target.other, NIL)), TRUE, false));
    reject(conflict, new ConflictInsertStatement(insert, List.of(target.id), assignments,
        new BooleanPredicate(false), false));
    assertDoesNotThrow(() -> validate(original, merge(target, source, update)));
    assertDoesNotThrow(() -> validate(conflict,
        new ConflictInsertStatement(insert, List.of(target.id), assignments, TRUE, false)));
  }

  @Test void returningRetainsItsTargetAndEverySelectedPosition() {
    var target = new Table("target");
    var update = update(target, target.name);
    var original = new ReturningStatement(update, List.of(target.name, target.other));
    reject(original, new ReturningStatement(update, List.of(target.name)));
    reject(original, new ReturningStatement(update, List.of(target.other, target.name)));
    reject(original, new ReturningStatement(update, List.of(target.id, target.other)));
    var other = new Table("other");
    reject(original, new ReturningStatement(update(other, other.name), List.of(other.name, other.other)));
    assertDoesNotThrow(() -> validate(original, new ReturningStatement(update, List.of(target.name, target.other))));
  }

  private static UpdateStatement update(Table table, ColumnExpression<Object, String> column) {
    return new UpdateStatement(table, List.of(new UpdateAssignment<>(column, NIL)), TRUE);
  }

  private static MergeStatement merge(Table target, Table source, MergeAction action) {
    return new MergeStatement(target, RelationSource.entity(source), TRUE, List.of(action));
  }

  private static void reject(StatementAst original, StatementAst replacement) {
    assertThrows(IllegalArgumentException.class, () -> validate(original, replacement));
  }

  private static void validate(StatementAst original, StatementAst replacement) {
    LoweringCorrespondence.validate(original, replacement, false, DialectCapabilities.NONE);
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
