package io.skis.core;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable statement options whose unset fields inherit executor or JDBC driver defaults. */
public final class ExecutionOptions {

  private static final int UNSET = -1;

  /** Empty per-statement options that inherit every executor or driver default. */
  public static final ExecutionOptions NONE =
      new ExecutionOptions(UNSET, null, UNSET, UNSET, false, null);

  private final int queryTimeoutSeconds;
  private final @Nullable Duration statementTimeout;
  private final int fetchSize;
  private final int maxRows;
  private final boolean queryTagConfigured;
  private final @Nullable QueryTag queryTag;
  private final @Nullable ExecutionContext executionContext;

  private ExecutionOptions(
      int queryTimeoutSeconds,
      @Nullable Duration statementTimeout,
      int fetchSize,
      int maxRows,
      boolean queryTagConfigured,
      @Nullable QueryTag queryTag) {
    this.queryTimeoutSeconds = queryTimeoutSeconds;
    this.statementTimeout = statementTimeout;
    this.fetchSize = fetchSize;
    this.maxRows = maxRows;
    this.queryTagConfigured = queryTagConfigured;
    this.queryTag = queryTag;
    boolean empty =
        queryTimeoutSeconds == UNSET
            && fetchSize == UNSET
            && maxRows == UNSET
            && !queryTagConfigured;
    this.executionContext = empty ? null : new OptionsExecutionContext(this);
  }

  /** Starts an empty mutable builder. Validation happens as each option is supplied. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns effective defaults after applying every field explicitly configured by {@code
   * overrides}.
   *
   * <p>This is intended for executor/session assembly, not per-statement execution. Empty overrides
   * reuse this instance and an empty base reuses the override instance.
   */
  public ExecutionOptions overriddenBy(ExecutionOptions overrides) {
    ExecutionOptions values = Objects.requireNonNull(overrides, "overrides");
    if (values.isEmpty()) {
      return this;
    }
    if (isEmpty()) {
      return values;
    }
    ExecutionOptions merged =
        new ExecutionOptions(
            values.hasStatementTimeout() ? values.queryTimeoutSeconds : queryTimeoutSeconds,
            values.hasStatementTimeout() ? values.statementTimeout : statementTimeout,
            values.hasFetchSize() ? values.fetchSize : fetchSize,
            values.hasMaxRows() ? values.maxRows : maxRows,
            values.queryTagConfigured || queryTagConfigured,
            values.queryTagConfigured ? values.queryTag : queryTag);
    return equals(merged) ? this : merged;
  }

  /** Returns whether no field overrides an executor default. */
  public boolean isEmpty() {
    return queryTimeoutSeconds == UNSET
        && fetchSize == UNSET
        && maxRows == UNSET
        && !queryTagConfigured;
  }

  ExecutionContext executionContext() {
    return requireSet(executionContext, "executionContext");
  }

  /** Returns whether a statement timeout override is present. */
  public boolean hasStatementTimeout() {
    return queryTimeoutSeconds != UNSET;
  }

  /** Returns the caller-facing timeout, or fails when this field is unset. */
  public Duration statementTimeout() {
    return requireSet(statementTimeout, "statementTimeout");
  }

  /** Returns the prevalidated JDBC timeout in whole seconds, or fails when unset. */
  public int queryTimeoutSeconds() {
    if (queryTimeoutSeconds == UNSET) {
      throw new IllegalStateException("statementTimeout is not configured");
    }
    return queryTimeoutSeconds;
  }

  /** Returns whether a fetch-size override is present. */
  public boolean hasFetchSize() {
    return fetchSize != UNSET;
  }

  /** Returns the configured JDBC fetch size, or fails when this field is unset. */
  public int fetchSize() {
    if (fetchSize == UNSET) {
      throw new IllegalStateException("fetchSize is not configured");
    }
    return fetchSize;
  }

  /** Returns whether a maximum-row override is present. */
  public boolean hasMaxRows() {
    return maxRows != UNSET;
  }

  /** Returns the configured JDBC maximum row count, or fails when this field is unset. */
  public int maxRows() {
    if (maxRows == UNSET) {
      throw new IllegalStateException("maxRows is not configured");
    }
    return maxRows;
  }

  /**
   * Returns whether this object explicitly configures tag behavior.
   *
   * <p>A configured {@code null} tag explicitly clears an executor default tag.
   */
  public boolean isQueryTagConfigured() {
    return queryTagConfigured;
  }

  /** Returns the configured tag, or {@code null} when an executor default is explicitly cleared. */
  public @Nullable QueryTag queryTag() {
    if (!queryTagConfigured) {
      throw new IllegalStateException("queryTag is not configured");
    }
    return queryTag;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ExecutionOptions that)) {
      return false;
    }
    return queryTimeoutSeconds == that.queryTimeoutSeconds
        && fetchSize == that.fetchSize
        && maxRows == that.maxRows
        && queryTagConfigured == that.queryTagConfigured
        && Objects.equals(statementTimeout, that.statementTimeout)
        && Objects.equals(queryTag, that.queryTag);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        queryTimeoutSeconds, statementTimeout, fetchSize, maxRows, queryTagConfigured, queryTag);
  }

  private static <T> T requireSet(@Nullable T value, String name) {
    if (value == null) {
      throw new IllegalStateException(name + " is not configured");
    }
    return value;
  }

  /** Mutable one-time builder for an immutable set of statement overrides. */
  public static final class Builder {

    private int queryTimeoutSeconds = UNSET;
    private @Nullable Duration statementTimeout;
    private int fetchSize = UNSET;
    private int maxRows = UNSET;
    private boolean queryTagConfigured;
    private @Nullable QueryTag queryTag;

    private Builder() {}

    /**
     * Sets the JDBC query timeout.
     *
     * <p>Positive fractional seconds round up so a positive timeout never becomes JDBC's zero
     * (unlimited) value. Zero is retained as an explicit request to disable a configured timeout.
     */
    public Builder statementTimeout(Duration timeout) {
      Duration value = Objects.requireNonNull(timeout, "timeout");
      if (value.isNegative()) {
        throw new IllegalArgumentException("statementTimeout must not be negative");
      }
      long seconds = value.getSeconds();
      if (value.getNano() != 0) {
        if (seconds == Long.MAX_VALUE) {
          throw new IllegalArgumentException(
              "statementTimeout cannot be represented by JDBC whole seconds");
        }
        seconds++;
      }
      if (seconds > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(
            "statementTimeout cannot be represented by JDBC whole seconds");
      }
      this.queryTimeoutSeconds = (int) seconds;
      this.statementTimeout = value;
      return this;
    }

    /** Sets the JDBC fetch-size hint; zero explicitly requests the driver default. */
    public Builder fetchSize(int fetchSize) {
      if (fetchSize < 0) {
        throw new IllegalArgumentException("fetchSize must not be negative");
      }
      this.fetchSize = fetchSize;
      return this;
    }

    /**
     * Sets the JDBC maximum row count; zero explicitly removes the limit.
     *
     * <p>For {@code fetchOne()}, SKIS may raise a positive value of one to two so it can retain
     * non-unique-result detection.
     */
    public Builder maxRows(int maxRows) {
      if (maxRows < 0) {
        throw new IllegalArgumentException("maxRows must not be negative");
      }
      this.maxRows = maxRows;
      return this;
    }

    /** Sets a prevalidated query tag. */
    public Builder queryTag(QueryTag queryTag) {
      this.queryTagConfigured = true;
      this.queryTag = Objects.requireNonNull(queryTag, "queryTag");
      return this;
    }

    /** Validates and sets a query tag. */
    public Builder queryTag(String queryTag) {
      return queryTag(QueryTag.of(queryTag));
    }

    /** Explicitly suppresses an executor default query tag for this statement. */
    public Builder clearQueryTag() {
      this.queryTagConfigured = true;
      this.queryTag = null;
      return this;
    }

    /**
     * Creates the immutable options, reusing {@link ExecutionOptions#NONE} when no field was
     * supplied.
     */
    public ExecutionOptions build() {
      if (queryTimeoutSeconds == UNSET
          && fetchSize == UNSET
          && maxRows == UNSET
          && !queryTagConfigured) {
        return NONE;
      }
      return new ExecutionOptions(
          queryTimeoutSeconds, statementTimeout, fetchSize, maxRows, queryTagConfigured, queryTag);
    }
  }
}
