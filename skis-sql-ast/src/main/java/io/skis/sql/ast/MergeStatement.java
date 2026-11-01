package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Native ordered MERGE USING actions, or the independent single-row KEY grammar. */
public record MergeStatement(
    TableExpression<?> target,
    @Nullable RelationSource source,
    SqlPredicate on,
    List<MergeAction> actions,
    List<ColumnExpression<?, ?>> insertColumns,
    List<SqlExpression<?>> insertValues,
    List<ColumnExpression<?, ?>> keys)
    implements StatementAst {

  public MergeStatement(
      TableExpression<?> target,
      RelationSource source,
      SqlPredicate on,
      List<MergeAction> actions) {
    this(target, source, on, actions, List.of(), List.of(), List.of());
  }

  public MergeStatement {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(on, "on");
    actions = List.copyOf(actions);
    insertColumns = List.copyOf(insertColumns);
    insertValues = List.copyOf(insertValues);
    keys = List.copyOf(keys);
    if (source == null
        ? keys.isEmpty()
            || !actions.isEmpty()
            || insertColumns.isEmpty()
            || insertColumns.size() != insertValues.size()
        : !keys.isEmpty()
            || !insertColumns.isEmpty()
            || !insertValues.isEmpty()
            || actions.isEmpty())
      throw new IllegalArgumentException(
          "MERGE requires a source and complete actions, or independent KEY values");
  }

  public boolean keyForm() {
    return source == null;
  }

  public boolean hasDelete() {
    return actions.stream().anyMatch(action -> action.kind() == MergeAction.Kind.DELETE);
  }

  public boolean onlyDelete() {
    return !actions.isEmpty()
        && actions.stream().allMatch(action -> action.kind() == MergeAction.Kind.DELETE);
  }

  public FromClause scope() {
    return source == null
        ? FromClause.of(target)
        : new FromClause(target, List.of(new JoinClause(JoinType.CROSS, source, null)));
  }
}
