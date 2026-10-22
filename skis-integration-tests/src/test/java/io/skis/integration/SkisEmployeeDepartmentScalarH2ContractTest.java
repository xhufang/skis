package io.skis.integration;

import io.skis.dialect.h2.H2Dialect;
import io.skis.dialect.spi.Dialect;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

class SkisEmployeeDepartmentScalarH2ContractTest
    extends AbstractSkisEmployeeDepartmentScalarContractTest {
  @Override
  protected DataSource createDataSource() {
    JdbcDataSource result = new JdbcDataSource();
    result.setURL("jdbc:h2:mem:employee_scalar_" + UUID.randomUUID());
    return result;
  }

  @Override
  protected Dialect dialect() {
    return H2Dialect.INSTANCE;
  }
}
