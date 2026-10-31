package io.skis.mutation.internal;

import io.skis.core.NonUniqueResultException;
import io.skis.mutation.Written;
import io.skis.query.SingleRow;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Detached row result with compact empty/single storage and no execution resources. */
final class DefaultWritten<R extends @Nullable Object> implements Written<R> {
  private final int affectedRows;
  private final int size;
  private final @Nullable R value;
  private final @Nullable List<R> many;

  DefaultWritten(int affectedRows, @Nullable R value) {
    if (affectedRows < 0 || affectedRows > 1)
      throw new IllegalArgumentException("single-row result requires count zero or one");
    this.affectedRows = affectedRows;
    this.size = affectedRows;
    this.value = value;
    this.many = null;
  }

  private DefaultWritten(List<R> rows, int affectedRows) {
    if (affectedRows < 0)
      throw new IllegalArgumentException("affected rows must be exact and non-negative");
    this.affectedRows = affectedRows;
    this.size = rows.size();
    this.value = size == 1 ? rows.getFirst() : null;
    this.many = size > 1 ? rows : null;
  }

  /** The JDBC row buffer has already been detached and exposed as an immutable list. */
  static <R extends @Nullable Object> DefaultWritten<R> ownedRows(int affectedRows, List<R> rows) {
    return new DefaultWritten<>(rows, affectedRows);
  }

  @Override
  public int affectedRows() {
    return affectedRows;
  }

  @Override
  public List<R> rows() {
    if (many != null) return many;
    return size == 0 ? List.of() : Collections.singletonList(value);
  }

  @Override
  public SingleRow<R> one() {
    if (size > 1) throw NonUniqueResultException.of("write returned multiple rows");
    return size == 0 ? SingleRow.noRow() : SingleRow.present(value);
  }

  @Override
  public String toString() {
    return "Written[values redacted]";
  }
}
