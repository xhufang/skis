package io.skis.mapping;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Generated reflection-free binders and version access used by entity mutation Fast Paths. */
public record EntityMutationBinders<E>(
    ParameterBinder<E> insert,
    ParameterBinder<E> updateById,
    ParameterBinder<E> updateByIdUnchecked,
    @Nullable EntityVersionReader<E> versionReader) {

  /** Validates the generated mutation bindings. */
  public EntityMutationBinders {
    Objects.requireNonNull(insert, "insert");
    Objects.requireNonNull(updateById, "updateById");
    Objects.requireNonNull(updateByIdUnchecked, "updateByIdUnchecked");
  }
}
