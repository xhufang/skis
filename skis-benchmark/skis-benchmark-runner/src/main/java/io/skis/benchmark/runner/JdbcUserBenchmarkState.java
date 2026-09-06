package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariDataSource;
import io.skis.benchmark.jdbc.JdbcUser;
import io.skis.benchmark.jdbc.JdbcUserRepository;
import java.util.List;
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

  @Param({"benchmark"})
  public String username;

  private HikariDataSource dataSource;
  private JdbcUserRepository repository;

  @Setup(Level.Trial)
  public void setUp() {
    dataSource = BenchmarkDatabase.openDataSource("jdbc");
    repository = new JdbcUserRepository(dataSource);
    requireUser(repository.findById(userId));
    requireUsers(repository.findAll());
    requireUsers(repository.findByUsername(username));
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataSource.close();
  }

  public JdbcUser findById() {
    return repository.findById(userId);
  }

  public List<JdbcUser> findAll() {
    return repository.findAll();
  }

  public List<JdbcUser> findByUsername() {
    return repository.findByUsername(username);
  }

  private void requireUser(JdbcUser user) {
    if (user == null) {
      throw new IllegalStateException("JDBC did not find skis_user id " + userId);
    }
  }

  private void requireUsers(List<JdbcUser> users) {
    if (users.isEmpty()) {
      throw new IllegalStateException("JDBC did not find the Fast Path benchmark row");
    }
  }
}
