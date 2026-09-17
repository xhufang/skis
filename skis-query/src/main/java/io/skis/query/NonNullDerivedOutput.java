package io.skis.query;

import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;

/** Explicit derived-output handle whose completed inner expression is required to be non-null. */
public final class NonNullDerivedOutput<V> extends DerivedOutput<V> {

  NonNullDerivedOutput(NonNullSelectable<V> selectable, Identifier alias) {
    super(selectable, alias, Nullability.NON_NULL);
  }
}
