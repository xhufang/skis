package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** H2 single-row MERGE USING, or its distinct KEY grammar. */
public record MergeStatement(
    TableExpression<?> target,
    @Nullable ParameterRelationSource source,
    SqlPredicate on,
    SqlPredicate matchedWhere,
    List<UpdateAssignment<?>> assignments,
    List<ColumnExpression<?, ?>> insertColumns,
    List<SqlExpression<?>> insertValues,
    List<ColumnExpression<?, ?>> keys)
    implements StatementAst {

  public MergeStatement {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(on, "on");
    Objects.requireNonNull(matchedWhere, "matchedWhere");
    assignments = List.copyOf(assignments);
    insertColumns = List.copyOf(insertColumns);
    insertValues = List.copyOf(insertValues);
    keys = List.copyOf(keys);
    if (insertColumns.size() != insertValues.size()
        || (source == null) == keys.isEmpty()
        || assignments.isEmpty() && insertColumns.isEmpty()) {
      throw new IllegalArgumentException(
          "MERGE requires a source and complete actions, or KEY values");
    }
  }

  public boolean keyForm() {
    return source == null;
  }

  public FromClause scope() {
    return source == null
        ? FromClause.of(target)
        : new FromClause(target, List.of(new JoinClause(JoinType.CROSS, source, null)));
  }
}
