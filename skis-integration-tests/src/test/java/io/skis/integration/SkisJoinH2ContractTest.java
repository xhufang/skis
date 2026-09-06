package io.skis.integration;

import io.skis.dialect.Dialect;
import io.skis.dialect.h2.H2Dialect;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** Runs the complete Join contract against the H2 development dialect. */
class SkisJoinH2ContractTest extends AbstractSkisJoinContractTest {

  @Override
  protected DataSource createDataSource() {
    JdbcDataSource result = new JdbcDataSource();
    result.setURL(
        "jdbc:h2:mem:skis_join_"
            + UUID.randomUUID().toString().replace('-', '_')
            + ";DB_CLOSE_DELAY=-1");
    return result;
  }

  @Override
  protected Dialect dialect() {
    return H2Dialect.INSTANCE;
  }
}
