package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Immutable single-table DELETE statement with a required safety predicate. */
public record DeleteStatement(TableExpression<?> target, SqlPredicate where)
    implements StatementAst {

  /** Validates the target and required WHERE predicate. */
  public DeleteStatement {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(where, "where");
    ParameterSlotValidator.validate(List.of(), where);
  }
}
