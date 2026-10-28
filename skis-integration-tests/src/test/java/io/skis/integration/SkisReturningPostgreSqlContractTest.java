package io.skis.integration;

import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.dialect.spi.Dialect;
import io.skis.testsupport.PostgresContract;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

@PostgresContract
class SkisReturningPostgreSqlContractTest extends AbstractSkisReturningContractTest {
  private final PGSimpleDataSource source;
  SkisReturningPostgreSqlContractTest(PGSimpleDataSource source) { this.source = source; }
  @Override protected DataSource createDataSource() { return source; }
  @Override protected Dialect dialect() { return PostgreSqlDialect.INSTANCE; }
}
