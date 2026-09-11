package io.skis.sql.ast;

import java.util.Objects;

/** Stable identity of one relation occurrence in one resolved query block. */
public record ResolvedSourceIdentity(QueryBlockPath blockPath, int occurrenceOrdinal) {

  public ResolvedSourceIdentity {
    Objects.requireNonNull(blockPath, "blockPath");
    if (occurrenceOrdinal < 0) {
      throw new IllegalArgumentException("occurrenceOrdinal must not be negative");
    }
  }

  @Override
  public String toString() {
    return blockPath + "/source[" + occurrenceOrdinal + ']';
  }
}
