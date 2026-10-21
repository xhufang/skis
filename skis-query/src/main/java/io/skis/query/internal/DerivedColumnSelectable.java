package io.skis.query.internal;

import io.skis.query.DerivedOutput;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullDerivedOutput;
import io.skis.query.NonNullSelectable;
import io.skis.query.Selectable;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.util.Objects;

/** Package-owned selectable for one output ordinal of a concrete derived occurrence. */
sealed class DerivedColumnSelectable<V> implements Selectable<V>
    permits NonNullDerivedColumnSelectable {

  private final DerivedRelation relation;
  private final DerivedOutput<V> output;
  private final DerivedColumnExpression<V> expression;

  DerivedColumnSelectable(DerivedRelation relation, DerivedOutput<V> output, int ordinal) {
    this.relation = Objects.requireNonNull(relation, "relation");
    this.output = Objects.requireNonNull(output, "output");
    this.expression =
        new DerivedColumnExpression<>(QueryModels.relation(relation).reference(), ordinal);
  }

  @Override
  public final Class<V> javaType() {
    return output.javaType();
  }

  @Override
  public final SqlType sqlType() {
    return output.sqlType();
  }

  @Override
  public final Nullability nullability() {
    return output.nullability();
  }

  final SqlExpression<V> expression() {
    return expression;
  }

  final DerivedRelation relation() {
    return relation;
  }

  final DerivedOutput<V> output() {
    return output;
  }

  final int outputOrdinal() {
    return expression.outputOrdinal();
  }
}

/** Derived-column selectable that preserves a verified non-null inner output contract. */
final class NonNullDerivedColumnSelectable<V> extends DerivedColumnSelectable<V>
    implements NonNullSelectable<V> {

  NonNullDerivedColumnSelectable(
      DerivedRelation relation, NonNullDerivedOutput<V> output, int ordinal) {
    super(relation, output, ordinal);
  }
}
