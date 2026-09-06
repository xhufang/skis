package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariDataSource;
import io.skis.benchmark.skis.SkisUser;
import io.skis.benchmark.skis.SkisUserRepository;
import io.skis.query.SelectQuery;
import java.util.List;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class SkisUserBenchmarkState {

  @Param({"888"})
  public Long userId;

  @Param({"benchmark"})
  public String username;

  private HikariDataSource dataSource;
  private SkisUserRepository repository;
  private SelectQuery<SkisUser, SkisUser> usernameQuery;

  @Setup(Level.Trial)
  public void setUp() {
    dataSource = BenchmarkDatabase.openDataSource("skis");
    repository = new SkisUserRepository(dataSource);
    usernameQuery = repository.queryByUsername(username);
    requireUser(repository.findById(userId));
    requireUsers(repository.findAll());
    requireUsers(usernameQuery.fetchList());
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataSource.close();
  }

  public SkisUser findById() {
    return repository.findById(userId);
  }

  public List<SkisUser> findAll() {
    return repository.findAll();
  }

  public List<SkisUser> findByUsername() {
    return usernameQuery.fetchList();
  }

  private void requireUser(SkisUser user) {
    if (user == null) {
      throw new IllegalStateException("SKIS did not find skis_user id " + userId);
    }
  }

  private void requireUsers(List<SkisUser> users) {
    if (users.isEmpty()) {
      throw new IllegalStateException("SKIS did not find the Fast Path benchmark row");
    }
  }
}
