package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariDataSource;
import io.skis.benchmark.mybatisplus.MybatisPlusUser;
import io.skis.benchmark.mybatisplus.MybatisPlusUserRepository;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class MybatisPlusUserBenchmarkState {

  @Param({"888"})
  public Long userId;

  private HikariDataSource dataSource;
  private MybatisPlusUserRepository repository;

  @Setup(Level.Trial)
  public void setUp() {
    dataSource = BenchmarkDatabase.openDataSource("mybatis-plus");
    repository = new MybatisPlusUserRepository(dataSource);
    requireUser(repository.findById(userId));
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataSource.close();
  }

  public MybatisPlusUser findById() {
    return repository.findById(userId);
  }

  private void requireUser(MybatisPlusUser user) {
    if (user == null) {
      throw new IllegalStateException("MyBatis-Plus did not find skis_user id " + userId);
    }
  }
}
