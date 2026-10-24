package io.skis.integration;

import io.skis.dialect.h2.H2Dialect;
import io.skis.dialect.spi.Dialect;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

class SkisAggregateH2ContractTest extends AbstractSkisAggregateContractTest {

  @Override
  protected DataSource createDataSource() {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:aggregate_" + UUID.randomUUID());
    return source;
  }

  @Override
  protected Dialect dialect() {
    return H2Dialect.INSTANCE;
  }
}
