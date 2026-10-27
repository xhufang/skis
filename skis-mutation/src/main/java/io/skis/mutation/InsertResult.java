package io.skis.mutation;

import java.util.Objects;

/**
 * One confirmed insert and the entity carrying its primary key; not a transaction commit receipt.
 */
public record InsertResult<E>(int affectedRows, E entity) {
  public InsertResult {
    if (affectedRows != 1) {
      throw new IllegalArgumentException("an insert result requires exactly one affected row");
    }
    Objects.requireNonNull(entity, "entity");
  }

  @Override
  public String toString() {
    return "InsertResult[values redacted]";
  }
}
