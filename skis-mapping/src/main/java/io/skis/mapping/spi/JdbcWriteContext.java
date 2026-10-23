package io.skis.mapping.spi;

/**
 * Write-time services made available to JDBC codecs and parameter binders.
 *
 * <p>This contract currently carries no services. Explicit parameter observation uses the separate
 * {@link ParameterBinder#bindObserved} channel, so normal binding does not inspect logging state.
 */
public interface JdbcWriteContext {

  /** Context for binders that do not need dialect-specific write services. */
  JdbcWriteContext EMPTY = new JdbcWriteContext() {};
}
