package io.skis.dialect.postgresql;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.ExceptionClassifier;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;

/** PostgreSQL dialect composition root for the currently supported SQL subset. */
public final class PostgreSqlDialect implements Dialect {

  static final String ID = "postgresql";

  private static final DialectCapabilities CAPABILITIES =
      DialectCapabilities.of(
          DialectFeature.SCHEMA_QUALIFIED_TABLES,
          DialectFeature.PARAMETERIZED_LIMIT,
          DialectFeature.PARAMETERIZED_OFFSET,
          DialectFeature.NULLS_FIRST_LAST,
          DialectFeature.COUNT_DISTINCT);

  /** Stateless shared dialect instance. */
  public static final PostgreSqlDialect INSTANCE = new PostgreSqlDialect();

  private PostgreSqlDialect() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public IdentifierRules identifierRules() {
    return StandardIdentifierRules.INSTANCE;
  }

  @Override
  public DialectCapabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  public SqlRenderer renderer() {
    return PostgreSqlRenderer.INSTANCE;
  }

  @Override
  public ExceptionClassifier exceptionClassifier() {
    return PostgreSqlExceptionClassifier.INSTANCE;
  }
}
