package io.skis.benchmark.skis;

import io.skis.benchmark.skis.skis.SkisUserMeta;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import java.util.Objects;
import javax.sql.DataSource;

/** Current-reactor SKIS implementation of the benchmark query. */
public final class SkisUserRepository {

  private final SkisExecutor executor;

  public SkisUserRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.executor = SkisExecutorFactory.create(dataSource, PostgreSqlDialect.INSTANCE);
  }

  public SkisUser findById(Long id) {
    return executor.findById(SkisUserMeta.ENTITY, id).orElse(null);
  }
}
