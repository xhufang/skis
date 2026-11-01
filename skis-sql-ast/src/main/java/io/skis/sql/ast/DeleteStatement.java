package io.skis.sql.ast;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable single-table DELETE statement with a required safety predicate. */
public record DeleteStatement(
    TableExpression<?> target, SqlPredicate where, @Nullable FromClause using)
    implements StatementAst {

  /** Validates the target and required WHERE predicate. */
  public DeleteStatement {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(where, "where");
    SemanticValidator.validateDelete(target, where, using);
  }

  public DeleteStatement(TableExpression<?> target, SqlPredicate where) {
    this(target, where, null);
  }

  public FromClause scope() {
    return MutationScope.of(target, using);
  }
}
