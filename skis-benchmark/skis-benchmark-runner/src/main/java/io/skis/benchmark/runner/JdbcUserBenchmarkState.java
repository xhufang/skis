package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariDataSource;
import io.skis.benchmark.jdbc.JdbcUser;
import io.skis.benchmark.jdbc.JdbcUserRepository;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class JdbcUserBenchmarkState {

  @Param({"888"})
  public Long userId;

  private HikariDataSource dataSource;
  private JdbcUserRepository repository;

  @Setup(Level.Trial)
  public void setUp() {
    dataSource = BenchmarkDatabase.openDataSource("jdbc");
    repository = new JdbcUserRepository(dataSource);
    requireUser(repository.findById(userId));
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataSource.close();
  }

  public JdbcUser findById() {
    return repository.findById(userId);
  }

  private void requireUser(JdbcUser user) {
    if (user == null) {
      throw new IllegalStateException("JDBC did not find skis_user id " + userId);
    }
  }
}
