package io.skis.dialect.h2;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.ExceptionClassifier;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;

/** H2 development-and-test dialect composition root. */
public final class H2Dialect implements Dialect {

  static final String ID = "h2";

  private static final DialectCapabilities CAPABILITIES =
      DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);

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
  public SqlRenderer renderer() {
    return H2Renderer.INSTANCE;
  }

  @Override
  public ExceptionClassifier exceptionClassifier() {
    return H2ExceptionClassifier.INSTANCE;
  }
}
