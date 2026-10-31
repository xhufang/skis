package io.skis.query;

import io.skis.query.internal.QueryDsl;

/** A selectable whose declared expression value is non-null. */
public interface NonNullSelectable<V> extends Selectable<V> {

  @Override
  default NonNullSelectable<V> as(String alias) {
    return QueryDsl.alias(this, alias);
  }
}
