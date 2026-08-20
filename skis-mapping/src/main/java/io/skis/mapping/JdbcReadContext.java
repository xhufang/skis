package io.skis.mapping;

/**
 * Read-time services made available to JDBC codecs.
 *
 * <p>The first runtime slice deliberately keeps this contract empty. Future capabilities must be
 * introduced as default methods so generated decoders remain binary compatible.
 */
public interface JdbcReadContext {

  /** Context for codecs that do not need dialect-specific read services. */
  JdbcReadContext EMPTY = new JdbcReadContext() {};
}
