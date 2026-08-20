package io.skis.mapping;

/** Additional row-level services available while decoding a result set. */
public interface RowReadContext extends JdbcReadContext {

  /** Context for generated decoders that need no row-level services. */
  RowReadContext EMPTY = new RowReadContext() {};
}
