package io.skis.dialect.support;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.*;
import io.skis.metadata.*;
import io.skis.sql.ast.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredWritePipelineTest {
  private static final PropertyMeta<Object, Long> ID = new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Object, String> NAME = new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", false));
  private static final class Table extends TableExpression<Object> {
    Table() { super(EntityMeta.simple(Object.class, TableMeta.of("item"), List.of(ID, NAME), new PrimaryKeyMeta<>(List.of(ID)), false)); }
    @Override public Table as(Identifier alias) { throw new UnsupportedOperationException(); }
    ColumnExpression<Object, Long> id() { return column(ID); }
    ColumnExpression<Object, String> name() { return column(NAME); }
  }

  private static Dialect dialect(boolean pg, DialectLowering replacement) {
    return new Dialect() {
      final DialectCapabilities features = pg ? DialectCapabilities.of(DialectFeature.INSERT_ON_CONFLICT,
          DialectFeature.MUTATION_RETURNING) : DialectCapabilities.of(DialectFeature.MERGE_USING,
          DialectFeature.MERGE_KEY, DialectFeature.MUTATION_RETURNING, DialectFeature.MUTATION_RESULT_TABLE);
      @Override public String id() { return pg ? "test-pg" : "test-h2"; }
      @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
      @Override public DialectCapabilities capabilities() { return features; }
      @Override public SqlRenderer renderer() { return pg ? StandardSqlRenderer.postgresql(this) : StandardSqlRenderer.h2(this); }
      @Override public DialectLowering lowering() {
        return replacement != null ? replacement
            : pg ? StandardSqlLowering.postgresql(features) : StandardSqlLowering.h2(features);
      }
    };
  }

  @Test void conflictGoldenRetainsExcludedWhereAndRepeatedParameterLayout() {
    var table = new Table(); var id = new ParameterSlot<>(0, Long.class, false);
    var name = new ParameterSlot<>(1, String.class, false);
    var insert = new InsertStatement(table, List.of(table.id(), table.name()), List.of(id, name));
    var statement = new ConflictInsertStatement(insert, List.of(table.id()),
        List.of(new UpdateAssignment<>(table.name(), new ExcludedExpression<>(table.name()))), table.id().eq(id), false);
    var compiled = SqlCompiler.compile(new ReturningStatement(statement, List.of(table.id(), table.name())), dialect(true, null));
    assertEquals("INSERT INTO \"item\" (\"id\", \"name\") VALUES (?, ?) ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\" WHERE \"item\".\"id\" = ? RETURNING \"id\", \"name\"", compiled.sql());
    assertEquals(List.of(id, name, id), compiled.parameterLayout().slots());
    assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect(false, null)));
    assertThrows(IllegalArgumentException.class, () -> new UpdateStatement(table,
        List.of(new UpdateAssignment<>(table.name(), new ExcludedExpression<>(table.name()))), new BooleanPredicate(true)));
  }

  @Test void mergeUsingAndKeyHaveDistinctGoldenShapesAndFinalTableResults() {
    var table = new Table(); var id = new ParameterSlot<>(0, Long.class, false);
    var name = new ParameterSlot<>(1, String.class, false);
    var reference = new DerivedRelationReference(Identifier.of("s"), List.of(
        new DerivedOutputColumn(Identifier.of("id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
        new DerivedOutputColumn(Identifier.of("name"), String.class, SqlType.VARCHAR, Nullability.NON_NULL)));
    var source = new ParameterRelationSource(List.of(id, name), reference);
    var sourceId = new DerivedColumnExpression<Long>(reference, 0);
    var sourceName = new DerivedColumnExpression<String>(reference, 1);
    var merge = new MergeStatement(table, source, table.id().eq(sourceId), new BooleanPredicate(true),
        List.of(new UpdateAssignment<>(table.name(), sourceName)), List.of(table.id(), table.name()),
        List.of(sourceId, sourceName), List.of());
    var compiled = SqlCompiler.compile(new ReturningStatement(merge, List.of(table.id())), dialect(false, null));
    assertTrue(compiled.sql().startsWith("SELECT \"id\" FROM FINAL TABLE (MERGE INTO \"item\" USING "));
    assertTrue(compiled.sql().contains(" ON \"item\".\"id\" = \"s\".\"id\" WHEN MATCHED AND TRUE THEN UPDATE SET \"name\" = \"s\".\"name\" WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (\"s\".\"id\", \"s\".\"name\")"));
    assertEquals(List.of(id, name), compiled.parameterLayout().slots());
    var key = new MergeStatement(table, null, new BooleanPredicate(true), new BooleanPredicate(true), List.of(),
        List.of(table.id(), table.name()), List.of(id, name), List.of(table.id()));
    assertEquals("MERGE INTO \"item\" (\"id\", \"name\") KEY (\"id\") VALUES (?, ?)", SqlCompiler.compile(key, dialect(false, null)).sql());
    assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(merge, dialect(true, null)));
    assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(key, dialect(true, null)));
    assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(merge, dialect(false, input -> key)));
  }

  @Test void resolvedReturningPreservesValidatedMutationAndRejectsForeignTargets() {
    var table = new Table();
    var id = new ParameterSlot<>(0, Long.class, false);
    var name = new ParameterSlot<>(1, String.class, false);
    var insert = new InsertStatement(table, List.of(table.id(), table.name()), List.of(id, name));
    var conflict = new ConflictInsertStatement(insert, List.of(table.id()),
        List.of(new UpdateAssignment<>(table.name(), new ExcludedExpression<>(table.name()))),
        new BooleanPredicate(true), false);
    var resolved = ResolvedStatement.analyze(conflict);
    var returning = resolved.returning(List.of(table.id(), table.name()));
    assertSame(conflict, assertInstanceOf(ReturningStatement.class, returning.statement()).mutation());
    assertFalse(returning.hasQueryBlockAnalysis());
    var target = dialect(true, null);
    var raw = SqlCompiler.compile(new ReturningStatement(conflict, List.of(table.id(), table.name())), target);
    var compiled = SqlCompiler.compile(returning, target);
    assertEquals(raw.sql(), compiled.sql());
    assertEquals(raw.parameterLayout().positions(), compiled.parameterLayout().positions());
    assertThrows(IllegalArgumentException.class, () -> resolved.returning(List.of(new Table().id())));
    assertThrows(IllegalArgumentException.class, () -> resolved.returning(List.of()));
    assertThrows(IllegalArgumentException.class, () -> returning.returning(List.of(table.id())));
    var query = ResolvedStatement.analyze(new SelectStatement(List.of(table.id()), table));
    assertThrows(IllegalArgumentException.class, () -> query.returning(List.of(table.id())));
  }

  @Test void mergeInsertTypesArithmeticOperandsAndKeepsTheirParameterPositions() {
    var table = new Table();
    var name = new ParameterSlot<>(0, String.class, false);
    var numerator = new ParameterSlot<>(1, Long.class, false);
    var denominator = new ParameterSlot<>(2, Long.class, false);
    var reference = new DerivedRelationReference(Identifier.of("s"), List.of(
        new DerivedOutputColumn(Identifier.of("name"), String.class, SqlType.VARCHAR, Nullability.NON_NULL)));
    var source = new ParameterRelationSource(List.of(name), reference);
    var quotient = new ArithmeticExpression<>(numerator, ArithmeticOperator.DIVIDE, denominator);
    var merge = new MergeStatement(table, source, new BooleanPredicate(false), new BooleanPredicate(true),
        List.of(), List.of(table.id(), table.name()),
        List.of(quotient, new DerivedColumnExpression<>(reference, 0)), List.of());

    var compiled = SqlCompiler.compile(merge, dialect(false, null));
    assertTrue(compiled.sql().endsWith(
        "WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES ((CAST(? AS BIGINT) / CAST(? AS BIGINT)), \"s\".\"name\")"));
    assertEquals(List.of(name, numerator, denominator), compiled.parameterLayout().slots());
  }
}
