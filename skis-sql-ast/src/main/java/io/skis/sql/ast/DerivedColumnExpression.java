package io.skis.sql.ast;

import java.util.Objects;

/** Typed reference to one output ordinal of one concrete derived-relation occurrence. */
public record DerivedColumnExpression<T>(DerivedRelationReference relation, int outputOrdinal)
    implements SqlExpression<T> {

  public DerivedColumnExpression(DerivedRelationReference relation, int outputOrdinal) {
    this.relation = Objects.requireNonNull(relation, "relation");
    relation.output(outputOrdinal);
    this.outputOrdinal = outputOrdinal;
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
        || other
                instanceof
                DerivedColumnExpression<?>(DerivedRelationReference relation1, int ordinal)
            && outputOrdinal == ordinal
            && relation.equals(relation1);
  }
}
