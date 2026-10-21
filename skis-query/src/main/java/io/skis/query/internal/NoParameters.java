package io.skis.query.internal;

/** Allocation-free marker passed to a compiled plan that has no JDBC parameters. */
enum NoParameters {
  INSTANCE
}
