package io.skis.sql.ast;

import java.util.Objects;
import java.util.Optional;

/** Stable query-block-local position assigned to one relation source in a FROM clause. */
public record TableOccurrence(int occurrenceOrdinal, RelationSource source) {

  public TableOccurrence {
    if (occurrenceOrdinal < 0) {
      throw new IllegalArgumentException("occurrenceOrdinal must not be negative");
    }
    Objects.requireNonNull(source, "source");
  }

  /** Convenience constructor retaining the exact entity table reference in an adapter node. */
  public TableOccurrence(int occurrenceOrdinal, TableExpression<?> table) {
    this(occurrenceOrdinal, RelationSource.entity(table));
  }

  /** Alias when present, otherwise the source-defined name used to qualify columns. */
  public String effectiveQualifier() {
    return source.effectiveQualifier();
  }

  /** Returns the underlying entity table for an entity source. */
  public Optional<TableExpression<?>> entityTable() {
    return source.entityTable();
  }
}
