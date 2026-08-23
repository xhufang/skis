package io.skis.sql.ast;

/** A typed boolean expression usable as an SQL predicate. */
public interface SqlPredicate extends SqlExpression<Boolean> {

  @Override
  default Class<Boolean> javaType() {
    return Boolean.class;
  }
}
