package io.skis.mapping;

/**
 * Write-time services made available to JDBC codecs and parameter binders.
 *
 * <p>The first runtime slice deliberately keeps this contract empty. Future capabilities must be
 * introduced as default methods so generated binders remain binary compatible.
 */
public interface JdbcWriteContext {

  /** Context for binders that do not need dialect-specific write services. */
  JdbcWriteContext EMPTY = new JdbcWriteContext() {};
}
