package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Explicit mutation outputs; the result-table form is selected by dialect lowering. */
public record ReturningStatement(
    StatementAst mutation, List<ColumnExpression<?, ?>> selections, Form form)
    implements StatementAst {

  public enum Form {
    NATIVE,
    FINAL_TABLE,
    OLD_TABLE
  }

  public ReturningStatement(StatementAst mutation, List<ColumnExpression<?, ?>> selections) {
    this(mutation, selections, Form.NATIVE);
  }

  public ReturningStatement {
    Objects.requireNonNull(mutation, "mutation");
    selections = List.copyOf(selections);
    Objects.requireNonNull(form, "form");
    TableExpression<?> target = targetOf(mutation);
    if (selections.isEmpty()) {
      throw new IllegalArgumentException("RETURNING requires explicit target columns");
    }
    for (ColumnExpression<?, ?> selection : selections) {
      if (selection.table() != target) {
        throw new IllegalArgumentException("RETURNING column is outside the mutation target");
      }
    }
    if (form == Form.OLD_TABLE && !(mutation instanceof DeleteStatement)
        || form == Form.FINAL_TABLE && mutation instanceof DeleteStatement) {
      throw new IllegalArgumentException("result-table form does not match mutation semantics");
    }
  }

  public TableExpression<?> target() {
    return targetOf(mutation);
  }

  private static TableExpression<?> targetOf(StatementAst mutation) {
    return switch (mutation) {
      case InsertStatement insert -> insert.target();
      case UpdateStatement update -> update.target();
      case DeleteStatement delete -> delete.target();
      default -> throw new IllegalArgumentException("RETURNING requires INSERT, UPDATE or DELETE");
    };
  }
}
