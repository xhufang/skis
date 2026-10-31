package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** SET (columns...) = (SELECT ...), preserving one evaluation of the query. */
public record RowAssignment(List<ColumnExpression<?, ?>> columns, SelectStatement query)
    implements SetAssignment {
  public RowAssignment {
    columns = List.copyOf(columns);
    Objects.requireNonNull(query, "query");
    // Hidden items are rendered as physical outputs and would break the row arity.
    if (columns.size() < 2
        || columns.size() != query.selections().size()
        || !query.hiddenSelections().isEmpty()) {
      throw new IllegalArgumentException(
          "row assignment requires matching column and SELECT arities");
    }
    for (int index = 0; index < columns.size(); index++) {
      ColumnExpression<?, ?> column = columns.get(index);
      SemanticValidator.validateAssignment(column, query.selections().get(index), "row UPDATE");
    }
  }
}
