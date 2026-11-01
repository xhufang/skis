package io.skis.mutation;

import io.skis.query.SingleRow;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** An already materialized write result; R describes one row, including legal scalar NULL. */
public interface Written<R extends @Nullable Object> {

  int affectedRows();

  List<R> rows();

  /** Requires at most one returned row and never executes SQL. */
  SingleRow<R> one();
}
