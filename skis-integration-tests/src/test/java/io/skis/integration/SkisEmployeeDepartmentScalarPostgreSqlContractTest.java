package io.skis.integration;

import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.dialect.spi.Dialect;
import io.skis.testsupport.PostgresContract;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

@PostgresContract
class SkisEmployeeDepartmentScalarPostgreSqlContractTest
    extends AbstractSkisEmployeeDepartmentScalarContractTest {
  private final PGSimpleDataSource postgres;

  SkisEmployeeDepartmentScalarPostgreSqlContractTest(PGSimpleDataSource postgres) {
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
