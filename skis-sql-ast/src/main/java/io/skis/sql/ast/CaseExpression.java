package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable searched SQL {@code CASE} expression. */
public record CaseExpression<T>(
    List<CaseWhen<T>> branches, Optional<SqlExpression<T>> otherwise)
    implements SqlExpression<T> {

  /** Creates a searched CASE with implicit {@code ELSE NULL}. */
  public CaseExpression(List<CaseWhen<T>> branches) {
    this(branches, Optional.empty());
  }

  /** Creates a searched CASE with an explicit ELSE expression. */
  public CaseExpression(List<CaseWhen<T>> branches, SqlExpression<T> otherwise) {
    this(branches, Optional.of(Objects.requireNonNull(otherwise, "otherwise")));
  }

  /** Defensively copies and validates the complete result shape. */
  public CaseExpression {
    Objects.requireNonNull(branches, "branches");
    Objects.requireNonNull(otherwise, "otherwise");
    branches = List.copyOf(branches);
    if (branches.isEmpty()) {
      throw new IllegalArgumentException("CASE requires at least one WHEN branch");
    }
    branches.forEach(branch -> Objects.requireNonNull(branch, "CASE branch"));
    SemanticValidator.validateCase(branches, otherwise);
  }

  @Override
  public Class<T> javaType() {
    return branches.getFirst().result().javaType();
  }

  @Override
  public SqlType sqlType() {
    return branches.getFirst().result().sqlType();
  }

  @Override
  public Nullability nullability() {
    if (otherwise.isEmpty() || otherwise.orElseThrow().nullable()) {
      return Nullability.NULLABLE;
    }
    return branches.stream().anyMatch(branch -> branch.result().nullable())
        ? Nullability.NULLABLE
        : Nullability.NON_NULL;
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }
}
