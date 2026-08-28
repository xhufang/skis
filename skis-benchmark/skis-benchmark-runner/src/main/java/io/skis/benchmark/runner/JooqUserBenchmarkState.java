package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariDataSource;
import io.skis.benchmark.jooq.JooqUser;
import io.skis.benchmark.jooq.JooqUserRepository;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class JooqUserBenchmarkState {

  @Param({"888"})
  public Long userId;

  private HikariDataSource dataSource;
  private JooqUserRepository repository;

  @Setup(Level.Trial)
  public void setUp() {
    dataSource = BenchmarkDatabase.openDataSource("jooq");
    repository = new JooqUserRepository(dataSource);
    requireUser(repository.findById(userId));
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataSource.close();
  }

  public JooqUser findById() {
    return repository.findById(userId);
  }

  private void requireUser(JooqUser user) {
    if (user == null) {
      throw new IllegalStateException("jOOQ did not find skis_user id " + userId);
    }
  }
}
