package io.skis.integration;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.testsupport.PostgresContract;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Runs the generated special-projection contract against PostgreSQL. */
@PostgresContract
class SkisSpecialProjectionPostgreSqlContractTest
    extends AbstractSkisSpecialProjectionContractTest {

  private final PGSimpleDataSource postgres;

  SkisSpecialProjectionPostgreSqlContractTest(PGSimpleDataSource postgres) {
    this.postgres = postgres;
  }

  @Override
  protected DataSource createDataSource() {
    return postgres;
  }

  @Override
  protected Dialect dialect() {
    return PostgreSqlDialect.INSTANCE;
  }

}
