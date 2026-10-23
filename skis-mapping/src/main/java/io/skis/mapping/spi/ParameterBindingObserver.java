package io.skis.mapping.spi;

import org.jspecify.annotations.Nullable;

/** Invocation-scoped synchronous sink used only by an explicitly selected observed binding path. */
public interface ParameterBindingObserver {

  /**
   * Whether another bound value is needed. Once false, binders may stop observing for the rest of
   * this invocation, while still binding every parameter. Check before boxing callback arguments.
   */
  default boolean needsMore() {
    return true;
  }

  /** Receives the existing binding local after JDBC binding; implementations must not retain it. */
  void bound(int jdbcIndex, Class<?> declaredJavaType, @Nullable Object value);

  /** Marks a custom binder whose internal values cannot be observed without repeating work. */
  void unavailable();
}
