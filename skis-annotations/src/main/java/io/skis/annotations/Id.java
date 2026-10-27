package io.skis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity property as part of its primary key.
 *
 * <p>An identifier property is implicitly mapped to a non-null column. Multiple {@code Id}
 * properties are reserved for an explicitly supported composite primary key. The annotation
 * processor is responsible for rejecting ambiguous declarations. Generation is explicit through
 * {@link #strategy()}; Java null may represent a not-yet-generated key without making its SQL
 * column nullable.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface Id {
  /** Selects the explicit primary-key assignment strategy. */
  IdStrategy strategy() default IdStrategy.ASSIGNED;
}
