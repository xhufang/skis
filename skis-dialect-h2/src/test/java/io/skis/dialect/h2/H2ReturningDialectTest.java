package io.skis.dialect.h2;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.dialect.support.SqlCompiler;
import io.skis.metadata.*;
import io.skis.sql.ast.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class H2ReturningDialectTest {
  private static final PropertyMeta<Object, Long> ID = new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Object, String> NAME = new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", true));
  private static final class Table extends TableExpression<Object> {
    Table() { super(EntityMeta.simple(Object.class, TableMeta.of("item"), List.of(ID, NAME), new PrimaryKeyMeta<>(List.of(ID)), false)); }
    @Override public Table as(Identifier alias) { throw new UnsupportedOperationException(); }
    ColumnExpression<Object, Long> id() { return column(ID); }
    ColumnExpression<Object, String> name() { return column(NAME); }
  }

  @Test
  void rendersAllReturningKindsAndKeepsFinalParameterAndOutputOrder() {
    var table = new Table();
    var id = new ParameterSlot<>(0, Long.class, false);
    var name = new ParameterSlot<>(1, String.class, true);
    var selections = List.<ColumnExpression<?, ?>>of(table.name(), table.id());
    var insert = new ReturningStatement(new InsertStatement(table, List.of(table.id(), table.name()), List.of(id, name)), selections);
    var inserted = SqlCompiler.compile(insert, H2Dialect.INSTANCE);
    assertEquals("SELECT \"name\", \"id\" FROM FINAL TABLE (INSERT INTO \"item\" (\"id\", \"name\") VALUES (?, ?))", inserted.sql());
    assertEquals(List.of(id, name), inserted.parameterLayout().slots());
    var update = new ReturningStatement(new UpdateStatement(table, List.of(new UpdateAssignment<>(table.name(), name)), table.id().eq(id)), selections);
    var updated = SqlCompiler.compile(update, H2Dialect.INSTANCE);
    assertEquals("SELECT \"name\", \"id\" FROM FINAL TABLE (UPDATE \"item\" SET \"name\" = ? WHERE \"id\" = ?)", updated.sql());
    assertEquals(List.of(name, id), updated.parameterLayout().slots());
    var delete = new ReturningStatement(new DeleteStatement(table, table.id().eq(id)), selections);
    var deleted = SqlCompiler.compile(delete, H2Dialect.INSTANCE);
    assertEquals("SELECT \"name\", \"id\" FROM OLD TABLE (DELETE FROM \"item\" WHERE \"id\" = ?)", deleted.sql());
    assertEquals(List.of(id), deleted.parameterLayout().slots());
    assertThrows(IllegalArgumentException.class, () -> new ReturningStatement(delete.mutation(), List.of(new Table().name())));
    assertThrows(IllegalArgumentException.class, () -> new ReturningStatement(insert.mutation(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new ReturningStatement(insert.mutation(), selections, ReturningStatement.Form.OLD_TABLE));
  }

  @Test
  void typesParameterOnlyAssignmentsAndPredicatesInBothWriteModes() {
    var table = new Table();
    var first = new ParameterSlot<>(0, String.class, true);
    var second = new ParameterSlot<>(1, String.class, false);
    var value = new CoalesceExpression<>(List.of(first, second));
    var where = new NullPredicate(first, NullOperator.IS_NULL);
    var update = new UpdateStatement(
        table, List.of(new UpdateAssignment<>(table.name(), value)), where);
    String updateSql = "UPDATE \"item\" SET \"name\" = COALESCE(CAST(? AS VARCHAR), CAST(? AS VARCHAR)) WHERE CAST(? AS VARCHAR) IS NULL";
    var count = SqlCompiler.compile(update, H2Dialect.INSTANCE);
    assertEquals(updateSql, count.sql());
    assertEquals(List.of(first, second, first), count.parameterLayout().slots());
    var returned = SqlCompiler.compile(
        new ReturningStatement(update, List.of(table.name())), H2Dialect.INSTANCE);
    assertEquals("SELECT \"name\" FROM FINAL TABLE (" + updateSql + ")", returned.sql());
    assertEquals(count.parameterLayout().slots(), returned.parameterLayout().slots());

    var delete = new DeleteStatement(table, where);
    String deleteSql = "DELETE FROM \"item\" WHERE CAST(? AS VARCHAR) IS NULL";
    assertEquals(deleteSql, SqlCompiler.compile(delete, H2Dialect.INSTANCE).sql());
    var deleted = SqlCompiler.compile(
        new ReturningStatement(delete, List.of(table.name())), H2Dialect.INSTANCE);
    assertEquals("SELECT \"name\" FROM OLD TABLE (" + deleteSql + ")", deleted.sql());
    assertEquals(List.of(first), deleted.parameterLayout().slots());
  }
}
