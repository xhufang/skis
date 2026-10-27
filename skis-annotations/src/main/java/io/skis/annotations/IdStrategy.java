package io.skis.annotations;

/** Primary-key assignment strategies understood by the annotation processor. */
public enum IdStrategy {
  ASSIGNED,
  IDENTITY,
  UUID,
  SNOWFLAKE
}
