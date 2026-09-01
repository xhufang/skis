package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Immutable single-row INSERT statement. */
public record InsertStatement(
    TableExpression<?> target,
    List<ColumnExpression<?, ?>> columns,
    List<SqlExpression<?>> values)
    implements StatementAst {

  /** Validates the target, column list, and matching values. */
  public InsertStatement {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(columns, "columns");
    Objects.requireNonNull(values, "values");
    columns = List.copyOf(columns);
    values = List.copyOf(values);
    if (columns.isEmpty()) {
      throw new IllegalArgumentException("INSERT requires at least one column");
    }
    if (columns.size() != values.size()) {
      throw new IllegalArgumentException("INSERT columns and values must have the same size");
    }
    for (int index = 0; index < columns.size(); index++) {
      Objects.requireNonNull(columns.get(index), "column");
      Objects.requireNonNull(values.get(index), "value");
    }
    SemanticValidator.validateInsert(target, columns, values);
  }
}
