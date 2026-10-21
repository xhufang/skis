package io.skis.query;

import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlType;

/** Explicit typed handle for one publicly named derived-relation output. */
public interface DerivedOutput<V> {

  /** Explicit SQL column alias exposed by the derived relation. */
  String alias();

  /** Boxed Java value type of this output. */
  Class<V> javaType();

  /** Portable SQL value type of this output. */
  SqlType sqlType();

  /** Declared output nullability, before an outer join can null-extend the relation. */
  Nullability nullability();
}
