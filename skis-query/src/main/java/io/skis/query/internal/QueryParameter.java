package io.skis.query.internal;

import io.skis.sql.ast.Nullability;

/**
 * Opaque, strongly typed reference to one query value.
 *
 * <p>A reference is identified by object identity. It deliberately contains neither a runtime value
 * nor a query-block, logical-slot, or JDBC parameter ordinal.
 */
interface QueryParameter<V> {

  /** Returns the boxed Java type accepted by this reference. */
  Class<V> javaType();

  /** Returns whether this reference accepts null when bound. */
  Nullability nullability();
}
