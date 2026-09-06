package io.skis.benchmark.skis;

import io.skis.benchmark.skis.skis.SkisUserMeta;
import io.skis.benchmark.skis.skis.SkisUserTable;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.query.SelectQuery;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Current-reactor SKIS implementation of the benchmark query. */
public final class SkisUserRepository {

  private final SkisExecutor executor;
  private final SkisUserTable user = SkisUserTable.SKIS_USER;
  private final SelectQuery<SkisUser, SkisUser> allUsers;

  public SkisUserRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.executor = SkisExecutorFactory.create(dataSource, PostgreSqlDialect.INSTANCE);
    this.allUsers = executor.selectFrom(user);
  }

  public SkisUser findById(Long id) {
    return executor.findById(SkisUserMeta.ENTITY, id).orElse(null);
  }

  public List<SkisUser> findAll() {
    return allUsers.fetchList();
  }

  public SelectQuery<SkisUser, SkisUser> queryByUsername(String username) {
    return executor.selectFrom(user).where(user.username().eq(username));
  }
}
