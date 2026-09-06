package io.skis.query;

import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.SqlPredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query-layer join retaining its value-separated DSL condition until statement compilation. */
record QueryJoin(JoinType type, QueryTable<?> right, @Nullable QueryCondition on) {

  QueryJoin {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(right, "right");
    if (type == JoinType.CROSS && on != null) {
      throw new IllegalArgumentException("CROSS JOIN must not declare an ON condition");
    }
    if (type != JoinType.CROSS && on == null) {
      throw new IllegalArgumentException(type + " JOIN requires an ON condition");
    }
  }
}

/** Compiles every condition in SQL clause order with one dense parameter allocator. */
final class QueryStructureCompiler {

  private QueryStructureCompiler() {}

  static CompiledQueryStructure compile(
      QueryTable<?> root, List<QueryJoin> joins, @Nullable QueryCondition where) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(joins, "joins");
    QueryConditionCompiler compiler = new QueryConditionCompiler();
    List<JoinClause> joinAst = new ArrayList<>(joins.size());
    try {
      for (QueryJoin join : joins) {
        SqlPredicate on = join.on() == null ? null : QueryConditions.compile(join.on(), compiler);
        joinAst.add(new JoinClause(join.type(), join.right(), on));
      }
      SqlPredicate whereAst = where == null ? null : QueryConditions.compile(where, compiler);
      return new CompiledQueryStructure(
          new FromClause(root, joinAst),
          whereAst,
          compiler.parameterColumns(),
          compiler.arguments());
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }
}

record CompiledQueryStructure(
    FromClause fromClause,
    @Nullable SqlPredicate where,
    List<QueryColumn<?, ?>> parameterColumns,
    List<Object> arguments) {

  CompiledQueryStructure {
    Objects.requireNonNull(fromClause, "fromClause");
    parameterColumns = List.copyOf(parameterColumns);
    arguments = List.copyOf(arguments);
    if (parameterColumns.size() != arguments.size()) {
      throw new IllegalArgumentException("query parameter and argument counts differ");
    }
  }
}
