package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.metadata.EntityMeta;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Test fixtures assembled through the same structure compiler used by immutable queries. */
final class QueryTestSupport {

  private QueryTestSupport() {}

  static CompiledQueryStructure compile(
      QueryTable<?> root, List<QueryJoin> joins, @Nullable QueryCondition condition) {
    return compile(SelectedResult.entity(root), root, joins, condition);
  }

  static <R> CompiledQueryStructure compile(
      SelectedResult<R> selected,
      QueryTable<?> root,
      List<QueryJoin> joins,
      @Nullable QueryCondition condition) {
    SelectQueryState<R> state = SelectQueryState.create(selected, root);
    for (QueryJoin join : joins) {
      state = state.appendJoin(join.type(), join.right(), join.on());
    }
    return (condition == null ? state : state.where(condition)).structure();
  }

  static List<@Nullable Object> arguments(CompiledQueryStructure structure) {
    return structure.arguments(structure.validationStructure().parameters());
  }

  static <E> CompiledQueryPlan<E, Object> selectPlan(
      EntityPlanSet<E> plans, QueryTable<E> table, @Nullable QueryCondition condition) {
    return plans.selectPlanForStructure(table, compile(table, List.of(), condition));
  }

  static Object argument(QueryTable<?> table, @Nullable QueryCondition condition) {
    List<@Nullable Object> values = arguments(compile(table, List.of(), condition));
    return values.isEmpty() ? NoParameters.INSTANCE : new QueryArguments(values);
  }

  static QueryPlanDependencies dependencies(EntityMeta<?>... entities) {
    QueryTable<?> root = new RuntimeQueryTable<>(entities[0]).as(Identifier.of("source_0"));
    List<JoinClause> joins = new ArrayList<>();
    for (int index = 1; index < entities.length; index++) {
      QueryTable<?> joined =
          new RuntimeQueryTable<>(entities[index]).as(Identifier.of("source_" + index));
      joins.add(new JoinClause(JoinType.CROSS, joined, null));
    }
    return QueryPlanDependencies.from(
        SemanticValidator.analyzeComplete(
            new SelectStatement(root.selections(), new FromClause(root, joins), null)));
  }
}
