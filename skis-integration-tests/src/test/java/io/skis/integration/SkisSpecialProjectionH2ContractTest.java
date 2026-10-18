package io.skis.integration;

import io.skis.dialect.Dialect;
import io.skis.dialect.h2.H2Dialect;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** Runs the generated special-projection contract against H2. */
class SkisSpecialProjectionH2ContractTest extends AbstractSkisSpecialProjectionContractTest {

  @Override
  protected DataSource createDataSource() {
    JdbcDataSource result = new JdbcDataSource();
    result.setURL(
        "jdbc:h2:mem:skis_special_projection_"
            + UUID.randomUUID().toString().replace('-', '_')
            + ";DB_CLOSE_DELAY=-1");
    return result;
  }

  @Override
  protected Dialect dialect() {
    return H2Dialect.INSTANCE;
  }
}
