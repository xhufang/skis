package io.skis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the generated entity-property source for one special-projection constructor value. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface PropertyFrom {

  /** Complete generated property identity, for example {@code PetTable.ID}. */
  String value();

  /**
   * Required table alias when the source entity occurs more than once in a query block; a non-empty
   * alias must match {@code [A-Za-z_][A-Za-z0-9_]*}.
   */
  String alias() default "";
}
