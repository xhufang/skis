package io.skis.dialect.support;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.*;
import io.skis.metadata.*;
import io.skis.sql.ast.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReturningPipelineTest {
  private static final PropertyMeta<Object, Long> ID = new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Object, String> NAME = new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", true));
  private static final class Table extends TableExpression<Object> {
    Table() { super(EntityMeta.simple(Object.class, TableMeta.of("item"), List.of(ID, NAME), new PrimaryKeyMeta<>(List.of(ID)), false)); }
    @Override public Table as(Identifier alias) { throw new UnsupportedOperationException(); }
    ColumnExpression<Object, Long> id() { return column(ID); }
    ColumnExpression<Object, String> name() { return column(NAME); }
  }

  @Test
  void postValidationRejectsChangedOutputsTargetsPredicatesAndParameterDescriptors() {
    var table = new Table();
    var key = new ParameterSlot<>(0, Long.class, false);
    var original = new ReturningStatement(new DeleteStatement(table, table.id().eq(key)), List.of(table.id(), table.name()));
    for (ReturningStatement changed : List.of(
        new ReturningStatement(original.mutation(), List.of(table.name(), table.id())),
        new ReturningStatement(original.mutation(), List.of(table.id())),
        new ReturningStatement(new DeleteStatement(table, new BooleanPredicate(true)), original.selections()),
        new ReturningStatement(new DeleteStatement(table, table.id().eq(new ParameterSlot<>(0, Long.class, true))), original.selections()))) {
      assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(original, dialect(input -> changed, true)));
    }
    assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(original, dialect(input -> input.statement(), false)));
  }

  private static Dialect dialect(DialectLowering lowering, boolean returning) {
    return new Dialect() {
      private final SqlRenderer renderer = new StandardSqlRenderer(this);
      @Override public String id() { return "pipeline-test"; }
      @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
      @Override public DialectCapabilities capabilities() { return returning
          ? DialectCapabilities.of(DialectFeature.MUTATION_RETURNING) : DialectCapabilities.none(); }
      @Override public SqlRenderer renderer() { return renderer; }
      @Override public DialectLowering lowering() { return lowering; }
    };
  }
}
