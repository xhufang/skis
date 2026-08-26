package io.skis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a user record or class for generation of a reflection-free, entity-bound SKIS projection
 * mapper.
 *
 * <p>For a record, SKIS uses its canonical constructor. A class must expose exactly one public,
 * non-generic constructor or mark one public, non-generic constructor with {@link
 * ProjectionConstructor}. Every constructor parameter type, enclosing type, generic argument, and
 * type-use annotation must be accessible from the generated {@code .skis} subpackage. Constructor
 * parameter names select persistent properties from {@link #entity()}; use {@link
 * ProjectionProperty} when a result parameter has a different name.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface SkisProjection {

  /** Returns the entity whose persistent properties supply this projection. */
  Class<?> entity();
}
