package io.skis.mapping;

import org.jspecify.annotations.Nullable;

/** Generated provider loaded from the deterministic SKIS entity index. */
public interface EntityRuntimeModelProvider {

  /** Returns the canonical generated runtime model; loaders reject {@code null}. */
  @Nullable EntityRuntimeModel<?> model();
}
