package io.skis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java type as an entity managed by SKIS.
 *
 * <p>SKIS reads this annotation during annotation processing. Runtime code uses the generated
 * entity metadata instead of scanning the annotation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SkisEntity {

  /**
   * Whether the entity is query-only.
   *
   * <p>A read-only entity does not need a primary key, but it cannot be used by mutation APIs.
   */
  boolean readOnly() default false;
}
