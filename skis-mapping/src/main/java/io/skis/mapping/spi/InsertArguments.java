package io.skis.mapping.spi;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Invocation-local input to a generated insert binder; never retained by a compiled plan. */
public record InsertArguments<E>(E entity, @Nullable Object effectiveId) {
  public InsertArguments {
    Objects.requireNonNull(entity, "entity");
  }

  @Override
  public String toString() {
    return "InsertArguments[values redacted]";
  }
}
