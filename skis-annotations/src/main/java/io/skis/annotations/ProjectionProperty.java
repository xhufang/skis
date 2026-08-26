package io.skis.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Selects an entity property whose name differs from a projection constructor parameter. */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface ProjectionProperty {

  /** Returns the persistent entity property name. */
  String value();
}
