package io.skis.query.internal;

import io.skis.query.NonNullDerivedOutput;
import io.skis.query.NonNullSelectable;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;

/** Explicit derived-output handle whose completed inner expression is required to be non-null. */
final class DefaultNonNullDerivedOutput<V> extends DefaultDerivedOutput<V>
    implements NonNullDerivedOutput<V> {

  DefaultNonNullDerivedOutput(NonNullSelectable<V> selectable, Identifier alias) {
    super(selectable, alias, Nullability.NON_NULL);
  }
}
