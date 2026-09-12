package io.skis.query;

import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
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
    return compile(root, joins, where, List.of(), null);
  }

  static CompiledQueryStructure compile(SelectQueryState<?> state) {
    Objects.requireNonNull(state, "state");
    return compile(state.root(), state.joins(), state.where(), state.groupBy(), state.having());
  }

  private static CompiledQueryStructure compile(
      QueryTable<?> root,
      List<QueryJoin> joins,
      @Nullable QueryCondition where,
      List<Selectable<?>> groupBy,
      @Nullable QueryCondition having) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(joins, "joins");
    Objects.requireNonNull(groupBy, "groupBy");
    QueryConditionCompiler compiler = new QueryConditionCompiler();
    List<JoinClause> joinAst = new ArrayList<>(joins.size());
    try {
      for (QueryJoin join : joins) {
        SqlPredicate on = join.on() == null ? null : QueryConditions.compile(join.on(), compiler);
        joinAst.add(new JoinClause(join.type(), join.right(), on));
      }
      SqlPredicate whereAst = where == null ? null : QueryConditions.compile(where, compiler);
      List<SqlExpression<?>> groupByAst =
          groupBy.stream().<SqlExpression<?>>map(Selectable::expression).toList();
      SqlPredicate havingAst = having == null ? null : QueryConditions.compile(having, compiler);
      return new CompiledQueryStructure(
          new FromClause(root, joinAst),
          whereAst,
          groupByAst,
          havingAst,
          compiler.parameterSources(),
          compiler.parameterReferences(),
          compiler.parameterSlots(),
          compiler.parameters());
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }
}

record CompiledQueryStructure(
    FromClause fromClause,
    @Nullable SqlPredicate where,
    List<SqlExpression<?>> groupBy,
    @Nullable SqlPredicate having,
    List<Selectable<?>> parameterSources,
    List<QueryParameter<?>> parameterReferences,
    List<ParameterSlot<?>> parameterSlots,
    QueryParameters parameters) {

  CompiledQueryStructure {
    Objects.requireNonNull(fromClause, "fromClause");
    groupBy = List.copyOf(groupBy);
    parameterSources = List.copyOf(parameterSources);
    parameterReferences = List.copyOf(parameterReferences);
    parameterSlots = List.copyOf(parameterSlots);
    Objects.requireNonNull(parameters, "parameters");
    if (parameterSources.size() != parameterReferences.size()
        || parameterSources.size() != parameterSlots.size()) {
      throw new IllegalArgumentException(
          "query parameter reference, slot, and binder-source counts differ");
    }
    for (int ordinal = 0; ordinal < parameterSlots.size(); ordinal++) {
      if (parameterSlots.get(ordinal).ordinal() != ordinal) {
        throw new IllegalArgumentException("query parameter slots must be dense from zero");
      }
    }
  }

  CompiledQueryStructure(
      FromClause fromClause,
      @Nullable SqlPredicate where,
      List<Selectable<?>> parameterSources,
      List<QueryParameter<?>> parameterReferences,
      List<ParameterSlot<?>> parameterSlots,
      QueryParameters parameters) {
    this(
        fromClause,
        where,
        List.of(),
        null,
        parameterSources,
        parameterReferences,
        parameterSlots,
        parameters);
  }

  List<@Nullable Object> arguments() {
    parameters.validateFor(parameterReferences);
    return parameters.valuesFor(parameterReferences);
  }

  List<@Nullable Object> arguments(QueryParameters suppliedParameters) {
    Objects.requireNonNull(suppliedParameters, "suppliedParameters");
    suppliedParameters.validateFor(parameterReferences);
    return suppliedParameters.valuesFor(parameterReferences);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof CompiledQueryStructure structure
            && fromClause.equals(structure.fromClause)
            && Objects.equals(where, structure.where)
            && groupBy.equals(structure.groupBy)
            && Objects.equals(having, structure.having);
  }

  @Override
  public int hashCode() {
    int result = 31 * fromClause.hashCode() + Objects.hashCode(where);
    result = 31 * result + groupBy.hashCode();
    return 31 * result + Objects.hashCode(having);
  }
}
