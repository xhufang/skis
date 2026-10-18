package io.skis.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.skis.dialect.Dialect;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Runs the generated special-projection contract against PostgreSQL. */
class SkisSpecialProjectionPostgreSqlContractTest
    extends AbstractSkisSpecialProjectionContractTest {

  @Override
  protected DataSource createDataSource() {
    String url = System.getenv("SKIS_POSTGRES_URL");
    assumeTrue(url != null && url.startsWith("jdbc:postgresql:"));
    PGSimpleDataSource result = new PGSimpleDataSource();
    result.setURL(url);
    result.setUser(environmentOrDefault("SKIS_POSTGRES_USER", "skis"));
    result.setPassword(environmentOrDefault("SKIS_POSTGRES_PASSWORD", "skis"));
    return result;
  }

  @Override
  protected Dialect dialect() {
    return PostgreSqlDialect.INSTANCE;
  }

  private static String environmentOrDefault(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
