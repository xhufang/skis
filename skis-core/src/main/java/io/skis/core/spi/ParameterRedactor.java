package io.skis.core.spi;

import org.jspecify.annotations.Nullable;

/**
 * Trusted synchronous policy for explicitly enabled parameter summaries.
 *
 * <p>Implementations must be thread-safe, must not retain or modify the value, consume streams/LOBs,
 * access JDBC, or reenter SKIS. The returned text is application-approved output; escaping and length
 * limits cannot remove secrets deliberately returned by a custom policy.
 */
@FunctionalInterface
public interface ParameterRedactor {

  /** Reports only the declared type, without inspecting or formatting the value. */
  ParameterRedactor TYPE_ONLY = (jdbcIndex, declaredJavaType, value) -> declaredJavaType.getTypeName();

  /**
   * Redacts an already acquired value at this SQL's one-based, final JDBC slot.
   *
   * @return application-approved text, or {@code null} to emit the fixed redaction-failed marker
   */
  @Nullable String redact(int jdbcIndex, Class<?> declaredJavaType, @Nullable Object value);
}
