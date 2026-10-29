package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Callback-scoped collector of typed VALUES output handles and captured inputs. */
public interface ValuesRow {
  <V> ValuesRow value(NonNullDerivedOutput<V> output, V value);

  <V> ValuesRow value(DerivedOutput<V> output, @Nullable V value);
}
