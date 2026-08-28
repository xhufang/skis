package io.skis.benchmark.jimmer;

import java.util.Objects;
import javax.sql.DataSource;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.PostgresDialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;

/** Standalone Jimmer implementation of the benchmark query. */
public final class JimmerUserRepository {

  private final JSqlClient sqlClient;

  public JimmerUserRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.sqlClient =
        JSqlClient.newBuilder()
            .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
            .setDialect(new PostgresDialect())
            .build();
  }

  public JimmerUser findById(Long id) {
    return sqlClient.findById(JimmerUser.class, id);
  }
}
