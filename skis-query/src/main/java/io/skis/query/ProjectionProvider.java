package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Generated provider loaded once from the deterministic projection index. */
public interface ProjectionProvider {

  /** Returns the immutable generated projection definition; loaders reject {@code null}. */
  @Nullable Projection<?, ?> projection();
}
