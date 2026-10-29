package io.skis.core.spi;

import io.skis.core.SkisOperation;

/** Public terminal operation that owns an observed execution lifecycle. */
public enum ExecutionOperationKind {
  FIND_BY_ID("find-by-id", SkisOperation.QUERY),
  FETCH_ONE("fetch-one", SkisOperation.QUERY),
  FETCH_FIRST("fetch-first", SkisOperation.QUERY),
  FETCH_LIST("fetch-list", SkisOperation.QUERY),
  FETCH_PAGE("fetch-page", SkisOperation.QUERY),
  FETCH_SLICE("fetch-slice", SkisOperation.QUERY),
  CURSOR("cursor", SkisOperation.QUERY),
  STREAM("stream", SkisOperation.QUERY),
  INSERT("insert", SkisOperation.MUTATION),
  MERGE("merge", SkisOperation.MUTATION),
  UPDATE("update", SkisOperation.MUTATION),
  DELETE("delete", SkisOperation.MUTATION),
  UPDATE_BY_ID("update-by-id", SkisOperation.MUTATION),
  DELETE_BY_ID("delete-by-id", SkisOperation.MUTATION);

  private final String label;
  private final SkisOperation operation;

  ExecutionOperationKind(String label, SkisOperation operation) {
    this.label = label;
    this.operation = operation;
  }

  /** Returns the stable lower-case label. */
  public String label() {
    return label;
  }

  /** Returns the framework operation family used by safe diagnostics. */
  public SkisOperation operation() {
    return operation;
  }
}
