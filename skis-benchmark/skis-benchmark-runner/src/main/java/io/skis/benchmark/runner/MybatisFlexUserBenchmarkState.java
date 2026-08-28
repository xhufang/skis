package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariDataSource;
import io.skis.benchmark.mybatisflex.MybatisFlexUser;
import io.skis.benchmark.mybatisflex.MybatisFlexUserRepository;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class MybatisFlexUserBenchmarkState {

  @Param({"888"})
  public Long userId;

  private HikariDataSource dataSource;
  private MybatisFlexUserRepository repository;

  @Setup(Level.Trial)
  public void setUp() {
    dataSource = BenchmarkDatabase.openDataSource("mybatis-flex");
    repository = new MybatisFlexUserRepository(dataSource);
    requireUser(repository.findById(userId));
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    dataSource.close();
  }

  public MybatisFlexUser findById() {
    return repository.findById(userId);
  }

  private void requireUser(MybatisFlexUser user) {
    if (user == null) {
      throw new IllegalStateException("MyBatis-Flex did not find skis_user id " + userId);
    }
  }
}
