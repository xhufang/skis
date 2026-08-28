package io.skis.benchmark.runner;

import io.skis.benchmark.jdbc.JdbcUser;
import io.skis.benchmark.jimmer.JimmerUser;
import io.skis.benchmark.jooq.JooqUser;
import io.skis.benchmark.mybatis.MybatisUser;
import io.skis.benchmark.mybatisflex.MybatisFlexUser;
import io.skis.benchmark.mybatisplus.MybatisPlusUser;
import io.skis.benchmark.skis.SkisUser;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@Threads(1)
public class UserFindByIdBenchmark {

  @Benchmark
  public JdbcUser jdbc(JdbcUserBenchmarkState state) {
    return state.findById();
  }

  @Benchmark
  public SkisUser skis(SkisUserBenchmarkState state) {
    return state.findById();
  }

  @Benchmark
  public JimmerUser jimmer(JimmerUserBenchmarkState state) {
    return state.findById();
  }

  @Benchmark
  public MybatisUser mybatis(MybatisUserBenchmarkState state) {
    return state.findById();
  }

  @Benchmark
  public MybatisFlexUser mybatisFlex(MybatisFlexUserBenchmarkState state) {
    return state.findById();
  }

  @Benchmark
  public MybatisPlusUser mybatisPlus(MybatisPlusUserBenchmarkState state) {
    return state.findById();
  }

  @Benchmark
  public JooqUser jooq(JooqUserBenchmarkState state) {
    return state.findById();
  }
}
