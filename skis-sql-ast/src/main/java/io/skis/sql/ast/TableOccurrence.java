package io.skis.sql.ast;

import java.util.Objects;

/** Stable query-block-local position assigned to one table reference in a FROM clause. */
public record TableOccurrence(int occurrenceOrdinal, TableExpression<?> table) {

  public TableOccurrence {
    if (occurrenceOrdinal < 0) {
      throw new IllegalArgumentException("occurrenceOrdinal must not be negative");
    }
    Objects.requireNonNull(table, "table");
  }

  /** Alias when present, otherwise the physical table name used to qualify columns. */
  public String effectiveQualifier() {
    return table.alias().map(Identifier::value).orElse(table.entity().table().name());
  }
}
