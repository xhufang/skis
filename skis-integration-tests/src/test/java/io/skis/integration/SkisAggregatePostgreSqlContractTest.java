package io.skis.integration;

import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.dialect.spi.Dialect;
import io.skis.testsupport.PostgresContract;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

@PostgresContract
class SkisAggregatePostgreSqlContractTest extends AbstractSkisAggregateContractTest {
  private final PGSimpleDataSource postgres;

  SkisAggregatePostgreSqlContractTest(PGSimpleDataSource postgres) {
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
