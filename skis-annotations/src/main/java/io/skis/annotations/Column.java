package io.skis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps an entity property to a database column.
 *
 * <p>An empty name means that the annotation processor should apply the configured naming strategy.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface Column {

  /** Returns the physical column name, or an empty string to use the naming strategy. */
  String name() default "";

  /** Whether the database column accepts SQL {@code NULL}. */
  boolean nullable() default true;

  /** Whether the column participates in generated insert statements. */
  boolean insertable() default true;

  /** Whether the column participates in generated update statements. */
  boolean updatable() default true;

  /** Maximum character or binary length. A value of {@code 0} means unspecified. */
  int length() default 255;

  /** Numeric precision. A value of {@code 0} means unspecified. */
  int precision() default 0;

  /** Numeric scale. A value of {@code 0} means unspecified. */
  int scale() default 0;

  /** Optional schema documentation used by DDL export. */
  String comment() default "";
}
