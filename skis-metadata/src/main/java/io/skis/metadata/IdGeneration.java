package io.skis.metadata;

/** Compiled primary-key generation policy; independent of runtime annotations. */
public enum IdGeneration {
  ASSIGNED,
  IDENTITY,
  UUID,
  SNOWFLAKE
}
