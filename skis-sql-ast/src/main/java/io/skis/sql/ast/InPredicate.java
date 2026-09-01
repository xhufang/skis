package io.skis.sql.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable SQL {@code IN} or {@code NOT IN} predicate with a defensive candidate copy. */
public record InPredicate<T>(
    SqlExpression<T> value, List<? extends SqlExpression<T>> candidates, boolean negated)
    implements SqlPredicate {

  public InPredicate(
      SqlExpression<T> value, List<? extends SqlExpression<T>> candidates, boolean negated) {
    this.value = Objects.requireNonNull(value, "value");
    Objects.requireNonNull(candidates, "candidates");
    List<SqlExpression<T>> copy = new ArrayList<>(candidates.size());
    for (SqlExpression<T> candidate : candidates) {
      SqlExpression<T> checked = Objects.requireNonNull(candidate, "candidate");
      copy.add(checked);
    }
    this.candidates = List.copyOf(copy);
    this.negated = negated;
    SemanticValidator.validateIn(value, this.candidates);
  }

  @Override
  public Nullability nullability() {
    if (candidates.isEmpty()) {
      return Nullability.NON_NULL;
    }
    Nullability result = value.nullability();
    for (SqlExpression<T> candidate : candidates) {
      result = result.union(candidate.nullability());
    }
    return result;
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other
                instanceof
                InPredicate<?>(
                    SqlExpression<?> value1,
                    List<? extends SqlExpression<?>> candidates1,
                    boolean negated1)
            && negated == negated1
            && value.equals(value1)
            && candidates.equals(candidates1);
  }

  @Override
  public int hashCode() {
    int result = value.hashCode();
    result = 31 * result + candidates.hashCode();
    return 31 * result + Boolean.hashCode(negated);
  }
}
