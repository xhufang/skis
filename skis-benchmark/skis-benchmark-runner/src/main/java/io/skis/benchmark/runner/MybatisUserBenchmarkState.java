package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariDataSource;
import io.skis.benchmark.mybatis.MybatisUser;
import io.skis.benchmark.mybatis.MybatisUserRepository;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class MybatisUserBenchmarkState {

  @Param({"888"})
  public Long userId;

  private HikariDataSource dataSource;
  private MybatisUserRepository repository;

  @Setup(Level.Trial)
  public void setUp() {
    dataSource = BenchmarkDatabase.openDataSource("mybatis");
    repository = new MybatisUserRepository(dataSource);
    requireUser(repository.findById(userId));
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataSource.close();
  }

  public MybatisUser findById() {
    return repository.findById(userId);
  }

  private void requireUser(MybatisUser user) {
    if (user == null) {
      throw new IllegalStateException("MyBatis did not find skis_user id " + userId);
    }
  }
}
