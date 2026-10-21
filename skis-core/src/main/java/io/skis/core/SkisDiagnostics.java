package io.skis.core;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, value-free structured diagnostics attached to every {@link SkisException}.
 *
 * <p>Diagnostics describe stable structural facts only: the detection stage, query location,
 * dialect identity, statement kind and SQL fingerprint, driver SQLState and vendor code, and a
 * small set of cause-specific facts. They never contain complete SQL, bound values, decoded
 * results, AST nodes, JDBC resources, or arbitrary object details.
 *
 * <p>Unknown information stays absent. Producing diagnostics must not trigger an extra database
 * probe, render a statement, or call {@code toString()} on a caller value.
 */
public final class SkisDiagnostics {

  private final SkisDiagnosticStage stage;
  private final @Nullable QueryLocation query;
  private final @Nullable Dialect dialect;
  private final @Nullable Execution execution;
  private final @Nullable Driver driver;
  private final @Nullable Facts facts;

  private SkisDiagnostics(
      SkisDiagnosticStage stage,
      @Nullable QueryLocation query,
      @Nullable Dialect dialect,
      @Nullable Execution execution,
      @Nullable Driver driver,
      @Nullable Facts facts) {
    this.stage = Objects.requireNonNull(stage, "stage");
    this.query = query;
    this.dialect = dialect;
    this.execution = execution;
    this.driver = driver;
    this.facts = facts;
  }

  /** Creates diagnostics containing only the detection stage. */
  public static SkisDiagnostics of(SkisDiagnosticStage stage) {
    return new SkisDiagnostics(stage, null, null, null, null, null);
  }

  /** Creates diagnostics with a safe structural detail. */
  public static SkisDiagnostics of(SkisDiagnosticStage stage, String detail) {
    return new SkisDiagnostics(stage, null, null, null, null, new Facts(null, null, null, detail));
  }

  /** Starts a diagnostics builder at the supplied stage. */
  public static Builder builder(SkisDiagnosticStage stage) {
    return new Builder(stage);
  }

  /** Returns the detection stage. */
  public SkisDiagnosticStage stage() {
    return stage;
  }

  /** Returns query location details, or {@code null} when the failure has no query location. */
  public @Nullable QueryLocation query() {
    return query;
  }

  /** Returns dialect details, or {@code null} when the failure has no dialect context. */
  public @Nullable Dialect dialect() {
    return dialect;
  }

  /** Returns execution details, or {@code null} when the failure has no execution context. */
  public @Nullable Execution execution() {
    return execution;
  }

  /** Returns driver details, or {@code null} when the failure has no driver context. */
  public @Nullable Driver driver() {
    return driver;
  }

  /** Returns cause-specific facts, or {@code null} when the code needs no extra facts. */
  public @Nullable Facts facts() {
    return facts;
  }

  /**
   * Returns a copy with the supplied safe structural detail, preserving every existing group.
   *
   * <p>The detail is additive context such as an entity name or clause path. It must never contain
   * a bound value, a decoded result, complete SQL, or a rendered arbitrary object.
   */
  public SkisDiagnostics withDetail(String detail) {
    Objects.requireNonNull(detail, "detail");
    Facts merged =
        facts == null
            ? new Facts(null, null, null, detail)
            : new Facts(facts.expectedType(), facts.actualType(), facts.observedCount(), detail);
    return new SkisDiagnostics(stage, query, dialect, execution, driver, merged);
  }

  /**
   * Returns a deterministic, value-free rendering for messages, logs, and default listeners.
   *
   * <p>The rendering contains only the fixed diagnostic groups. It never includes a complete SQL
   * statement, a bound value, a decoded result, a Throwable, or a raw driver message.
   */
  public String safeText() {
    StringBuilder text = new StringBuilder("stage=").append(stage.label());
    if (query != null) {
      append(text, "queryBlock", query.blockPath());
      append(text, "clause", query.clause());
      append(text, "sourceIndex", query.sourceIndex());
      append(text, "sourceAlias", query.sourceAlias());
      append(text, "expressionPath", query.expressionPath());
    }
    if (dialect != null) {
      append(text, "dialect", dialect.dialectId());
      append(text, "product", dialect.productName());
      append(text, "productVersion", dialect.productVersion());
      append(text, "missingCapability", dialect.missingCapability());
    }
    if (execution != null) {
      append(
          text, "operation", execution.operation() == null ? null : execution.operation().label());
      append(text, "phase", execution.phase());
      append(
          text,
          "statementKind",
          execution.statementKind() == null ? null : execution.statementKind().label());
      append(text, "sqlFingerprint", execution.sqlFingerprint());
      append(text, "dataSource", execution.dataSourceId());
    }
    if (driver != null) {
      append(text, "sqlState", driver.sqlState());
      append(text, "vendorCode", driver.vendorCode());
      append(text, "category", driver.databaseCategory());
    }
    if (facts != null) {
      append(text, "expectedType", facts.expectedType());
      append(text, "actualType", facts.actualType());
      append(text, "observedCount", facts.observedCount());
      append(text, "detail", facts.detail());
    }
    return text.toString();
  }

  @Override
  public boolean equals(@org.jspecify.annotations.Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SkisDiagnostics that)) {
      return false;
    }
    return stage == that.stage
        && Objects.equals(query, that.query)
        && Objects.equals(dialect, that.dialect)
        && Objects.equals(execution, that.execution)
        && Objects.equals(driver, that.driver)
        && Objects.equals(facts, that.facts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(stage, query, dialect, execution, driver, facts);
  }

  @Override
  public String toString() {
    return safeText();
  }

  private static void append(StringBuilder text, String label, @Nullable Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String stringValue && stringValue.isBlank()) {
      return;
    }
    text.append(", ").append(label).append('=').append(value);
  }

  /** Stable query-block or expression location, expressed as structural paths and ordinals. */
  public record QueryLocation(
      @Nullable String blockPath,
      @Nullable String clause,
      @Nullable Integer sourceIndex,
      @Nullable String sourceAlias,
      @Nullable String expressionPath) {

    /** Validates that at least one location fact is present. */
    public QueryLocation {
      if (blockPath == null
          && clause == null
          && sourceIndex == null
          && sourceAlias == null
          && expressionPath == null) {
        throw new IllegalArgumentException("a query location requires at least one fact");
      }
      requireNonNegative(sourceIndex, "sourceIndex");
    }
  }

  /** Known dialect identity and missing-capability facts. */
  public record Dialect(
      @Nullable String dialectId,
      @Nullable String productName,
      @Nullable String productVersion,
      @Nullable String missingCapability) {

    /** Validates that at least one dialect fact is present. */
    public Dialect {
      if (dialectId == null
          && productName == null
          && productVersion == null
          && missingCapability == null) {
        throw new IllegalArgumentException("dialect diagnostics require at least one fact");
      }
    }
  }

  /**
   * Operation, JDBC lifecycle phase, statement kind, fingerprint, and known data-source identity.
   */
  public record Execution(
      @Nullable SkisOperation operation,
      @Nullable String phase,
      @Nullable SkisStatementKind statementKind,
      @Nullable String sqlFingerprint,
      @Nullable String dataSourceId) {

    /** Validates that at least one execution fact is present. */
    public Execution {
      if (operation == null
          && phase == null
          && statementKind == null
          && sqlFingerprint == null
          && dataSourceId == null) {
        throw new IllegalArgumentException("execution diagnostics require at least one fact");
      }
    }
  }

  /** Driver SQLState, non-zero vendor code, and the single authoritative database category. */
  public record Driver(
      @Nullable String sqlState, @Nullable Integer vendorCode, @Nullable String databaseCategory) {

    /** Validates that at least one driver fact is present. */
    public Driver {
      if (vendorCode != null && vendorCode == 0) {
        vendorCode = null;
      }
      if (sqlState == null && vendorCode == null && databaseCategory == null) {
        throw new IllegalArgumentException("driver diagnostics require at least one fact");
      }
    }
  }

  /** Cause-specific structural facts; never contains caller values or arbitrary objects. */
  public record Facts(
      @Nullable String expectedType,
      @Nullable String actualType,
      @Nullable Integer observedCount,
      @Nullable String detail) {

    /** Validates that at least one fact is present. */
    public Facts {
      requireNonNegative(observedCount, "observedCount");
      if (expectedType == null && actualType == null && observedCount == null && detail == null) {
        throw new IllegalArgumentException("cause facts require at least one fact");
      }
    }
  }

  private static void requireNonNegative(@Nullable Integer value, String label) {
    if (value != null && value < 0) {
      throw new IllegalArgumentException(label + " must not be negative");
    }
  }

  /** Mutable one-time builder for immutable diagnostics. */
  public static final class Builder {

    private final SkisDiagnosticStage stage;
    private @Nullable String blockPath;
    private @Nullable String clause;
    private @Nullable Integer sourceIndex;
    private @Nullable String sourceAlias;
    private @Nullable String expressionPath;
    private @Nullable String dialectId;
    private @Nullable String productName;
    private @Nullable String productVersion;
    private @Nullable String missingCapability;
    private @Nullable SkisOperation operation;
    private @Nullable String phase;
    private @Nullable SkisStatementKind statementKind;
    private @Nullable String sqlFingerprint;
    private @Nullable String dataSourceId;
    private @Nullable String sqlState;
    private @Nullable Integer vendorCode;
    private @Nullable String databaseCategory;
    private @Nullable String expectedType;
    private @Nullable String actualType;
    private @Nullable Integer observedCount;
    private @Nullable String detail;

    private Builder(SkisDiagnosticStage stage) {
      this.stage = Objects.requireNonNull(stage, "stage");
    }

    /** Sets a structural query-block path such as {@code $/WHERE[0]#0}. */
    public Builder queryBlock(String blockPath) {
      this.blockPath = blockPath;
      return this;
    }

    /** Sets a clause label such as {@code SELECT}, {@code WHERE}, or {@code ORDER BY}. */
    public Builder clause(String clause) {
      this.clause = clause;
      return this;
    }

    /** Sets a zero-based source occurrence ordinal. */
    public Builder sourceIndex(int sourceIndex) {
      this.sourceIndex = sourceIndex;
      return this;
    }

    /** Sets a structural source alias or effective qualifier. */
    public Builder sourceAlias(String sourceAlias) {
      this.sourceAlias = sourceAlias;
      return this;
    }

    /** Sets a structural expression path. */
    public Builder expressionPath(String expressionPath) {
      this.expressionPath = expressionPath;
      return this;
    }

    /** Sets a known dialect identifier. */
    public Builder dialectId(String dialectId) {
      this.dialectId = dialectId;
      return this;
    }

    /** Sets a known database product name and version. */
    public Builder product(String productName, @Nullable String productVersion) {
      this.productName = productName;
      this.productVersion = productVersion;
      return this;
    }

    /** Sets a missing dialect capability name, or {@code null} when it is unknown. */
    public Builder missingCapability(@Nullable String missingCapability) {
      this.missingCapability = missingCapability;
      return this;
    }

    /** Sets the owning operation. */
    public Builder operation(SkisOperation operation) {
      this.operation = operation;
      return this;
    }

    /**
     * Sets a stable lifecycle phase label such as {@code execution} or {@code connection-release}.
     */
    public Builder phase(String phase) {
      this.phase = phase;
      return this;
    }

    /** Sets the SQL statement kind. */
    public Builder statementKind(SkisStatementKind statementKind) {
      this.statementKind = statementKind;
      return this;
    }

    /** Sets a value-independent SQL fingerprint. */
    public Builder sqlFingerprint(String sqlFingerprint) {
      this.sqlFingerprint = sqlFingerprint;
      return this;
    }

    /** Sets a known data-source identifier. */
    public Builder dataSourceId(String dataSourceId) {
      this.dataSourceId = dataSourceId;
      return this;
    }

    /**
     * Sets the known driver SQLState and vendor code; either may be absent and zero means absent.
     */
    public Builder driver(@Nullable String sqlState, @Nullable Integer vendorCode) {
      this.sqlState = sqlState;
      this.vendorCode = vendorCode != null && vendorCode == 0 ? null : vendorCode;
      return this;
    }

    /** Sets the single authoritative database category name. */
    public Builder databaseCategory(String databaseCategory) {
      this.databaseCategory = databaseCategory;
      return this;
    }

    /** Sets expected and actual structural type names. */
    public Builder types(@Nullable String expectedType, @Nullable String actualType) {
      this.expectedType = expectedType;
      this.actualType = actualType;
      return this;
    }

    /** Sets an observed count lower bound. */
    public Builder observedCount(int observedCount) {
      this.observedCount = observedCount;
      return this;
    }

    /** Sets a safe structural detail. Values and complete SQL must never be supplied here. */
    public Builder detail(String detail) {
      this.detail = detail;
      return this;
    }

    /** Builds immutable diagnostics. Absent groups are omitted. */
    public SkisDiagnostics build() {
      QueryLocation query = queryLocation();
      Dialect dialect = dialect();
      Execution execution = execution();
      Driver driver = driver();
      Facts facts = facts();
      return new SkisDiagnostics(stage, query, dialect, execution, driver, facts);
    }

    private @Nullable QueryLocation queryLocation() {
      if (blockPath == null
          && clause == null
          && sourceIndex == null
          && sourceAlias == null
          && expressionPath == null) {
        return null;
      }
      return new QueryLocation(blockPath, clause, sourceIndex, sourceAlias, expressionPath);
    }

    private @Nullable Dialect dialect() {
      if (dialectId == null
          && productName == null
          && productVersion == null
          && missingCapability == null) {
        return null;
      }
      return new Dialect(dialectId, productName, productVersion, missingCapability);
    }

    private @Nullable Execution execution() {
      if (operation == null
          && phase == null
          && statementKind == null
          && sqlFingerprint == null
          && dataSourceId == null) {
        return null;
      }
      return new Execution(operation, phase, statementKind, sqlFingerprint, dataSourceId);
    }

    private @Nullable Driver driver() {
      if (sqlState == null && vendorCode == null && databaseCategory == null) {
        return null;
      }
      return new Driver(sqlState, vendorCode, databaseCategory);
    }

    private @Nullable Facts facts() {
      if (expectedType == null && actualType == null && observedCount == null && detail == null) {
        return null;
      }
      return new Facts(expectedType, actualType, observedCount, detail);
    }
  }
}
