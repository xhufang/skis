package io.skis.dialect.h2;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.ExceptionClassifier;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.spi.StatementParameterLimit;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;

/** H2 development-and-test dialect composition root. */
public final class H2Dialect implements Dialect {

  static final String ID = "h2";

  private static final DialectCapabilities CAPABILITIES =
      DialectCapabilities.of(
          DialectFeature.SCHEMA_QUALIFIED_TABLES,
          DialectFeature.PARAMETERIZED_LIMIT,
          DialectFeature.PARAMETERIZED_OFFSET,
          DialectFeature.COUNT_DISTINCT,
          DialectFeature.NULLS_FIRST_LAST,
          DialectFeature.INNER_JOIN,
          DialectFeature.LEFT_JOIN,
          DialectFeature.RIGHT_JOIN,
          DialectFeature.CROSS_JOIN,
          DialectFeature.EXISTS_SUBQUERY,
          DialectFeature.IN_SUBQUERY,
          DialectFeature.SCALAR_SUBQUERY,
          DialectFeature.CORRELATED_SUBQUERY,
          DialectFeature.DERIVED_TABLE);
  // No stable dialect-level maximum has been accepted for the supported H2 contract.
  private static final StatementParameterLimit MAX_STATEMENT_PARAMETERS =
      StatementParameterLimit.unknown();
  private static final SqlRenderer RENDERER =
      new StandardSqlRenderer(ID, StandardIdentifierRules.INSTANCE, CAPABILITIES);

  /** Stateless shared dialect instance. */
  public static final H2Dialect INSTANCE = new H2Dialect();

  private H2Dialect() {}

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
  public boolean supportsResolvedQueryValidation() {
    return true;
  }

  @Override
  public StatementParameterLimit maxStatementParameters() {
    return MAX_STATEMENT_PARAMETERS;
  }

  @Override
  public SqlRenderer renderer() {
    return RENDERER;
  }

  @Override
  public ExceptionClassifier exceptionClassifier() {
    return H2ExceptionClassifier.INSTANCE;
  }
}
