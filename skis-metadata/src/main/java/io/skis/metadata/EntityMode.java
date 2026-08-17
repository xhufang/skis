package io.skis.metadata;

/** Describes the representation strategy used by an entity. */
public enum EntityMode {
  /** A user-declared class or record without framework-managed loading state. */
  SIMPLE,

  /** An interface entity backed by a generated immutable implementation and loading-state mask. */
  MANAGED_IMMUTABLE
}
