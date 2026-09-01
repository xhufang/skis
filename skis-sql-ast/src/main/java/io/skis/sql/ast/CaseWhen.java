package io.skis.sql.ast;

import java.util.Objects;

/** One searched {@code CASE WHEN} branch. */
public record CaseWhen<T>(SqlPredicate condition, SqlExpression<T> result) {

  /** Creates a non-null condition/result pair. */
  public CaseWhen {
    Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(result, "result");
  }
}
