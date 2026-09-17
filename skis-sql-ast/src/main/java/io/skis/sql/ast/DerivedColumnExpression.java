package io.skis.sql.ast;

import java.util.Objects;

/** Typed reference to one output ordinal of one concrete derived-relation occurrence. */
public final class DerivedColumnExpression<T> implements SqlExpression<T> {

  private final DerivedRelationReference relation;
  private final int outputOrdinal;

  public DerivedColumnExpression(DerivedRelationReference relation, int outputOrdinal) {
    this.relation = Objects.requireNonNull(relation, "relation");
    relation.output(outputOrdinal);
    this.outputOrdinal = outputOrdinal;
  }

  public DerivedRelationReference relation() {
    return relation;
  }

  public int outputOrdinal() {
    return outputOrdinal;
  }

  public DerivedOutputColumn output() {
    return relation.output(outputOrdinal);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<T> javaType() {
    return (Class<T>) output().javaType();
  }

  @Override
  public SqlType sqlType() {
    return output().sqlType();
  }

  @Override
  public Nullability nullability() {
    return output().nullability();
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof DerivedColumnExpression<?> expression
            && outputOrdinal == expression.outputOrdinal
            && relation.equals(expression.relation);
  }

  @Override
  public int hashCode() {
    return 31 * relation.hashCode() + outputOrdinal;
  }
}
