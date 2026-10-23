package io.skis.core;

import io.skis.core.spi.ParameterRedactor;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable, opt-in SQL logging configuration shared by an executor and its sessions. */
public final class SqlLoggingOptions {

  /** Default configuration: no SQL, slow-query or parameter logging. */
  public static final SqlLoggingOptions NONE = new Builder().build();

  private final boolean sql;
  private final boolean parameters;
  private final @Nullable Duration slowQueryThreshold;
  private final ParameterRedactor parameterRedactor;

  private SqlLoggingOptions(Builder builder) {
    sql = builder.sql;
    parameters = builder.parameters;
    slowQueryThreshold = builder.slowQueryThreshold;
    parameterRedactor = builder.parameterRedactor;
  }

  /** Starts an independent logging configuration. */
  public static Builder builder() {
    return new Builder();
  }

  /** Whether value-free SQL lifecycle logging is explicitly configured. */
  public boolean sql() {
    return sql;
  }

  /** Whether parameter summaries are configured; DEBUG must also be enabled. */
  public boolean parameters() {
    return parameters;
  }

  /** Explicit slow-query threshold, or {@code null} when slow logging is disabled. */
  public @Nullable Duration slowQueryThreshold() {
    return slowQueryThreshold;
  }

  /** The thread-safe trusted policy used only by the selected summary binding channel. */
  public ParameterRedactor parameterRedactor() {
    return parameterRedactor;
  }

  /** One-time mutable configuration; registering a policy does not enable summaries. */
  public static final class Builder {
    private boolean sql;
    private boolean parameters;
    private @Nullable Duration slowQueryThreshold;
    private ParameterRedactor parameterRedactor = ParameterRedactor.TYPE_ONLY;

    private Builder() {}

    /** Enables value-free SQL lifecycle logging at {@code io.skis.sql} DEBUG. */
    public Builder sql(boolean enabled) {
      sql = enabled;
      return this;
    }

    /** Enables summaries when {@code io.skis.sql.parameters} DEBUG is also enabled. */
    public Builder parameters(boolean enabled) {
      parameters = enabled;
      return this;
    }

    /** Enables slow-query logging with an explicit positive threshold. */
    public Builder slowQueryThreshold(Duration threshold) {
      Objects.requireNonNull(threshold, "threshold");
      if (threshold.isZero() || threshold.isNegative()) {
        throw SkisConfigurationException.of(
            SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID, "slowQueryThreshold must be positive");
      }
      slowQueryThreshold = threshold;
      return this;
    }

    /** Registers a trusted policy without enabling parameter summaries. */
    public Builder parameterRedactor(ParameterRedactor redactor) {
      parameterRedactor = Objects.requireNonNull(redactor, "redactor");
      return this;
    }

    /** Captures an immutable snapshot; subsequent builder changes cannot affect it. */
    public SqlLoggingOptions build() {
      return new SqlLoggingOptions(this);
    }
  }
}
