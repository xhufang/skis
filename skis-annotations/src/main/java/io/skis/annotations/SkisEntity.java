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
 *
 * <p>A Simple Entity is a top-level public record or a top-level public concrete Bean without type
 * parameters or entity inheritance. A Bean exposes a public no-argument constructor and public
 * read/write accessors (or writable public fields) for its persistent properties. Lombok may
 * generate the required mutable Bean shape when it is active as an annotation processor;
 * immutable, builder-only, and all-arguments-only Bean shapes such as Lombok {@code @Value} are
 * not supported. Records remain supported through their canonical constructor.
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
