package io.skis.query;

import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
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
    return compile(root, joins, where, true);
  }

  private static CompiledQueryStructure compile(
      QueryTable<?> root,
      List<QueryJoin> joins,
      @Nullable QueryCondition where,
      boolean retainOriginalExpressions) {
    StatementParameterLayout layout =
        new StatementParameterLayout(retainOriginalExpressions);
    QueryParameterBindings bindings = new QueryParameterBindings();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    CompiledBlock block = compileBlock(root, joins, where, List.of(), null, compiler);
    CompiledQueryStructure structure = complete(List.of(), block, List.of(), layout, bindings);
    return layout.requiresRewrite()
        ? compile(root, joins, where, false).withValidationSource(structure)
        : structure;
  }

  static CompiledQueryStructure compile(SelectQueryState<?> state) {
    return compile(state, true);
  }

  private static CompiledQueryStructure compile(
      SelectQueryState<?> state, boolean retainOriginalExpressions) {
    Objects.requireNonNull(state, "state");
    StatementParameterLayout layout =
        new StatementParameterLayout(retainOriginalExpressions);
    QueryParameterBindings bindings = new QueryParameterBindings();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    List<SqlExpression<?>> selections = state.selected().compileExpressions(compiler);
    CompiledBlock block =
        compileBlock(
            state.root(), state.joins(), state.where(), state.groupBy(), state.having(), compiler);
    List<OrderByItem> orderBy = compileOrderBy(state, selections, compiler);
    CompiledQueryStructure structure = complete(selections, block, orderBy, layout, bindings);
    return layout.requiresRewrite()
        ? compile(state, false).withValidationSource(structure)
        : structure;
  }

  static CompiledQueryStructure compileCount(SelectQueryState<?> state) {
    Objects.requireNonNull(state, "state");
    StatementParameterLayout layout = new StatementParameterLayout(false);
    QueryParameterBindings bindings = new QueryParameterBindings();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    List<SqlExpression<?>> selections =
        state.distinct() ? state.selected().compileExpressions(compiler) : List.of();
    CompiledBlock block =
        compileBlock(
            state.root(), state.joins(), state.where(), state.groupBy(), state.having(), compiler);
    return complete(selections, block, List.of(), layout, bindings)
        .withValidationSource(state.structure().validationStructure());
  }

  static SelectStatement compileSubquery(
      SelectQueryState<?> state, StatementParameterLayout layout, QueryParameterBindings bindings) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(bindings, "bindings");
    state.validateDistinctOrdering();
    if (state.sqlPagination().mode() != SqlPaginationStructure.Mode.NONE) {
      throw new QueryValidationException(
          "embedded SELECT descriptions do not yet support SQL pagination");
    }
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    List<SqlExpression<?>> selections = state.selected().compileExpressions(compiler);
    CompiledBlock block =
        compileBlock(
            state.root(), state.joins(), state.where(), state.groupBy(), state.having(), compiler);
    List<OrderByItem> orderBy = compileOrderBy(state, selections, compiler);
    try {
      return new SelectStatement(
          state.distinct(),
          selections,
          List.of(),
          block.fromClause(),
          block.where(),
          block.groupBy(),
          block.having(),
          orderBy,
          null);
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }

  private static CompiledBlock compileBlock(
      QueryTable<?> root,
      List<QueryJoin> joins,
      @Nullable QueryCondition where,
      List<Selectable<?>> groupBy,
      @Nullable QueryCondition having,
      QueryConditionCompiler compiler) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(joins, "joins");
    Objects.requireNonNull(groupBy, "groupBy");
    List<JoinClause> joinAst = new ArrayList<>(joins.size());
    try {
      for (QueryJoin join : joins) {
        SqlPredicate on = join.on() == null ? null : QueryConditions.compile(join.on(), compiler);
        joinAst.add(new JoinClause(join.type(), join.right(), on));
      }
      SqlPredicate whereAst = where == null ? null : QueryConditions.compile(where, compiler);
      List<SqlExpression<?>> groupByAst = new ArrayList<>(groupBy.size());
      for (Selectable<?> item : groupBy) {
        groupByAst.add(compiler.expression(item));
      }
      SqlPredicate havingAst = having == null ? null : QueryConditions.compile(having, compiler);
      return new CompiledBlock(new FromClause(root, joinAst), whereAst, groupByAst, havingAst);
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }

  private static CompiledQueryStructure complete(
      List<SqlExpression<?>> selections,
      CompiledBlock block,
      List<OrderByItem> orderBy,
      StatementParameterLayout layout,
      QueryParameterBindings bindings) {
    return new CompiledQueryStructure(
        block.fromClause(),
        selections,
        block.where(),
        block.groupBy(),
        block.having(),
        orderBy,
        layout.parameterSources(),
        layout.parameterReferences(),
        layout.parameterSlots(),
        bindings.parameters(),
        null);
  }

  private static List<OrderByItem> compileOrderBy(
      SelectQueryState<?> state,
      List<SqlExpression<?>> selections,
      QueryConditionCompiler compiler) {
    List<OrderByItem> result = new ArrayList<>(state.orderBy().size());
    List<SelectableSupport.ExpressionIdentity> selectedIdentities =
        state.distinct() ? state.selected().expressionIdentities() : List.of();
    for (SortSpecification specification : state.orderBy()) {
      if (state.distinct()
          && specification.selectable() instanceof ScalarSubquerySelectable<?>) {
        int selectedIndex =
            selectedIdentities.indexOf(
                SelectableSupport.expressionIdentity(specification.selectable()));
        if (selectedIndex >= 0) {
          if (compiler.releaseOriginalExpressions()) {
            // DISTINCT scalar ordering renders the selected output ordinal. Reuse its final
            // expression/slots so no parameters exist only in the omitted ORDER BY subtree.
            result.add(specification.ast(selections.get(selectedIndex)));
            continue;
          }
          // Preserve the original ordering occurrence for scope validation before sharing it.
          compiler.recordSelectedOrdering();
        }
      }
      result.add(specification.ast(compiler.expression(specification.selectable())));
    }
    return List.copyOf(result);
  }

  private record CompiledBlock(
      FromClause fromClause,
      @Nullable SqlPredicate where,
      List<SqlExpression<?>> groupBy,
      @Nullable SqlPredicate having) {

    private CompiledBlock {
      Objects.requireNonNull(fromClause, "fromClause");
      groupBy = List.copyOf(groupBy);
    }
  }
}

record CompiledQueryStructure(
    FromClause fromClause,
    List<SqlExpression<?>> selections,
    @Nullable SqlPredicate where,
    List<SqlExpression<?>> groupBy,
    @Nullable SqlPredicate having,
    List<OrderByItem> orderBy,
    List<Selectable<?>> parameterSources,
    List<QueryParameter<?>> parameterReferences,
    List<ParameterSlot<?>> parameterSlots,
    QueryParameters parameters,
    @Nullable CompiledQueryStructure validationSource) {

  CompiledQueryStructure {
    Objects.requireNonNull(fromClause, "fromClause");
    selections = List.copyOf(selections);
    groupBy = List.copyOf(groupBy);
    orderBy = List.copyOf(orderBy);
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
        List.of(),
        where,
        List.of(),
        null,
        List.of(),
        parameterSources,
        parameterReferences,
        parameterSlots,
        parameters,
        null);
  }

  CompiledQueryStructure withValidationSource(CompiledQueryStructure source) {
    return new CompiledQueryStructure(
        fromClause,
        selections,
        where,
        groupBy,
        having,
        orderBy,
        parameterSources,
        parameterReferences,
        parameterSlots,
        parameters,
        Objects.requireNonNull(source, "source").validationStructure());
  }

  /** Complete source retained for checks before operands are removed or ordering reuses a selection. */
  CompiledQueryStructure validationStructure() {
    return validationSource == null ? this : validationSource;
  }

  List<@Nullable Object> arguments() {
    QueryParameters supplied = validationStructure().parameters();
    return arguments(supplied);
  }

  List<@Nullable Object> arguments(QueryParameters suppliedParameters) {
    Objects.requireNonNull(suppliedParameters, "suppliedParameters");
    suppliedParameters.validateFor(validationStructure().parameterReferences());
    return suppliedParameters.valuesFor(parameterReferences);
  }

  List<@Nullable Object> projectedArguments(QueryParameters validatedParameters) {
    return Objects.requireNonNull(validatedParameters, "validatedParameters")
        .valuesFor(parameterReferences);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof CompiledQueryStructure structure
            && fromClause.equals(structure.fromClause)
            && selections.equals(structure.selections)
            && Objects.equals(where, structure.where)
            && groupBy.equals(structure.groupBy)
            && Objects.equals(having, structure.having)
            && orderBy.equals(structure.orderBy);
  }

  @Override
  public int hashCode() {
    int result = 31 * fromClause.hashCode() + selections.hashCode();
    result = 31 * result + Objects.hashCode(where);
    result = 31 * result + groupBy.hashCode();
    result = 31 * result + Objects.hashCode(having);
    return 31 * result + orderBy.hashCode();
  }
}
