package io.skis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the database table mapped by an entity.
 *
 * <p>An empty name means that the annotation processor should apply the configured naming
 * strategy. Catalog and schema are optional qualifiers.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {

  /** Returns the physical table name, or an empty string to use the naming strategy. */
  String name() default "";

  /** Returns the physical schema name, or an empty string when it is unspecified. */
  String schema() default "";

  /** Returns the physical catalog name, or an empty string when it is unspecified. */
  String catalog() default "";
}
