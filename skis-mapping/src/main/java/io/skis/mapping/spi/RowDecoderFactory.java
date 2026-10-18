package io.skis.mapping.spi;

/** Creates a generated row decoder for an immutable result-set layout. */
@FunctionalInterface
public interface RowDecoderFactory<R> {

  /** Creates a decoder whose property reads follow the supplied layout. */
  RowDecoder<R> create(RowLayout layout);
}
