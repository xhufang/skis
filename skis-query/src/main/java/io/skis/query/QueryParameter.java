package io.skis.query;

import io.skis.sql.ast.Nullability;
import java.util.Optional;

/**
 * Opaque, strongly typed reference to one query value.
 *
 * <p>A reference is identified by object identity. It deliberately contains neither a runtime value
 * nor a query-block, logical-slot, or JDBC parameter ordinal. An optional name is diagnostic
 * information only and never participates in parameter identity.
 */
public interface QueryParameter<V> {

  /** Returns the boxed Java type accepted by this reference. */
  Class<V> javaType();

  /** Returns the optional diagnostic name; the name is not the parameter's identity. */
  Optional<String> diagnosticName();

  /** Returns whether this reference accepts null when bound. */
  Nullability nullability();
}
