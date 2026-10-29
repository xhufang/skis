package io.skis.integration;

import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.dialect.spi.Dialect;
import io.skis.testsupport.PostgresContract;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

@PostgresContract
class SkisUpsertPostgreSqlContractTest extends AbstractSkisUpsertContractTest {
  private final PGSimpleDataSource source;
  SkisUpsertPostgreSqlContractTest(PGSimpleDataSource source) { this.source=source; }
  @Override protected DataSource createDataSource() { return source; }
  @Override protected Dialect dialect() { return PostgreSqlDialect.INSTANCE; }
}
