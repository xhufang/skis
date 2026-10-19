package io.skis.dialect.spi;

import io.skis.sql.ast.ParameterSlot;

/**
 * Serializes a target-bound {@link RenderInput} whose statement has already been validated and
 * whose final parameter layout has already been computed.
 *
 * <p>A renderer must not perform semantic analysis, capability validation, structural rewriting, or
 * parameter layout. It only serializes the final structure: quoting identifiers, emitting the
 * syntax represented by its nodes, and writing one JDBC placeholder for every {@link ParameterSlot}
 * occurrence in the order defined by {@link RenderInput#parameterLayout()}.
 */
@FunctionalInterface
public interface SqlRenderer {

  /** Serializes the final statement or fails when an unsupported node reaches the renderer. */
  String render(RenderInput input);
}
