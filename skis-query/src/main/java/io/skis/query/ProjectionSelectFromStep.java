package io.skis.query;

/** FROM stage for a generated user projection whose selections may span multiple tables. */
public interface ProjectionSelectFromStep<R> {

  /**
   * Chooses an independent root; every selected expression is validated in the final Join scope.
   */
  <F> SelectQuery<F, R> from(QueryTable<F> root);
}
