package io.skis.sql.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable single-row INSERT; empty columns and values explicitly mean DEFAULT VALUES. */
public record InsertStatement(
    TableExpression<?> target,
    List<ColumnExpression<?, ?>> columns,
    List<SqlExpression<?>> values,
    List<List<SqlExpression<?>>> additionalRows,
    @Nullable SelectStatement query)
    implements StatementAst {

  /** Validates the target, column list, and matching values. */
  public InsertStatement {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(columns, "columns");
    Objects.requireNonNull(values, "values");
    columns = List.copyOf(columns);
    values = List.copyOf(values);
    additionalRows = copyRows(Objects.requireNonNull(additionalRows, "additionalRows"));
    if (query != null && (!values.isEmpty() || !additionalRows.isEmpty() || columns.isEmpty()))
      throw new IllegalArgumentException("INSERT query and VALUES are mutually exclusive");
    if (query == null && columns.size() != values.size()) {
      throw new IllegalArgumentException("INSERT columns and values must have the same size");
    }
    for (int index = 0; query == null && index < columns.size(); index++) {
      Objects.requireNonNull(columns.get(index), "column");
      Objects.requireNonNull(values.get(index), "value");
    }
    SemanticValidator.validateInsert(target, columns, values, additionalRows, query);
  }

  /**
   * Null-hostile immutable copies (List.of storage), so List.copyOf of the stored rows and of the
   * outer list returns the same instances and unchanged lowering keeps identity.
   */
  private static List<List<SqlExpression<?>>> copyRows(List<List<SqlExpression<?>>> rows) {
    if (rows.isEmpty()) return List.of();
    List<List<SqlExpression<?>>> copied = new ArrayList<>(rows.size());
    for (List<SqlExpression<?>> row : rows) copied.add(List.copyOf(row));
    return List.copyOf(copied);
  }

  public InsertStatement(
      TableExpression<?> target,
      List<ColumnExpression<?, ?>> columns,
      List<SqlExpression<?>> values) {
    this(target, columns, values, List.of(), null);
  }

  public static InsertStatement select(
      TableExpression<?> target, List<ColumnExpression<?, ?>> columns, SelectStatement query) {
    return new InsertStatement(
        target, columns, List.of(), List.of(), Objects.requireNonNull(query, "query"));
  }
}
