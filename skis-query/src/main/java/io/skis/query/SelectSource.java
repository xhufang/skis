package io.skis.query;

import org.jspecify.annotations.Nullable;

/** A SELECT that can be embedded without executing it or importing its execution context. */
public interface SelectSource<R extends @Nullable Object> {

  /** Forms a new FROM/USING occurrence whose outputs are inferred from this SELECT. */
  DerivedRelation as(String alias);
}
