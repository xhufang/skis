package io.skis.mutation;

/** Explicit batch transport; execution never switches strategies after a failure. */
public enum BatchMode {
  ADD_BATCH,
  VALUES
}
