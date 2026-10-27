package io.skis.dialect.postgresql;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.ExceptionClassifier;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.spi.StatementParameterLimit;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlLowering;
import io.skis.dialect.support.StandardSqlRenderer;

/** PostgreSQL dialect composition root for the currently supported SQL subset. */
public final class PostgreSqlDialect implements Dialect {

  static final String ID = "postgresql";

  private static final DialectCapabilities CAPABILITIES =
      DialectCapabilities.of(
          DialectFeature.SCHEMA_QUALIFIED_TABLES,
          DialectFeature.PARAMETERIZED_LIMIT,
          DialectFeature.PARAMETERIZED_OFFSET,
          DialectFeature.NULLS_FIRST_LAST,
          DialectFeature.COUNT_DISTINCT,
          DialectFeature.INNER_JOIN,
          DialectFeature.LEFT_JOIN,
          DialectFeature.RIGHT_JOIN,
          DialectFeature.FULL_JOIN,
          DialectFeature.CROSS_JOIN,
          DialectFeature.EXISTS_SUBQUERY,
          DialectFeature.IN_SUBQUERY,
          DialectFeature.SCALAR_SUBQUERY,
          DialectFeature.CORRELATED_SUBQUERY,
          DialectFeature.DERIVED_TABLE,
          DialectFeature.GROUP_BY,
          DialectFeature.HAVING,
          DialectFeature.COUNT_AGGREGATE,
          DialectFeature.SUM_AGGREGATE,
          DialectFeature.AVG_AGGREGATE,
          DialectFeature.MIN_AGGREGATE,
          DialectFeature.MAX_AGGREGATE,
          DialectFeature.JDBC_GENERATED_KEYS,
          DialectFeature.INSERT_DEFAULT_VALUES);
  // PgJDBC 42.7.11 reports Integer.MAX_VALUE in simple mode and 65,535 otherwise.
  // Use the connection-independent value as the conservative framework cap.
  private static final StatementParameterLimit MAX_STATEMENT_PARAMETERS =
      StatementParameterLimit.explicit(65_535);
  private static final DialectLowering LOWERING = StandardSqlLowering.postgresql(CAPABILITIES);

  /** Stateless shared dialect instance. */
  public static final PostgreSqlDialect INSTANCE = new PostgreSqlDialect();

  private final SqlRenderer renderer = StandardSqlRenderer.postgresql(this);

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
  public boolean hasStablePlanCacheIdentity() {
    return true;
  }

  @Override
  public StatementParameterLimit maxStatementParameters() {
    return MAX_STATEMENT_PARAMETERS;
  }

  @Override
  public DialectLowering lowering() {
    return LOWERING;
  }

  @Override
  public SqlRenderer renderer() {
    return renderer;
  }

  @Override
  public ExceptionClassifier exceptionClassifier() {
    return PostgreSqlExceptionClassifier.INSTANCE;
  }
}
