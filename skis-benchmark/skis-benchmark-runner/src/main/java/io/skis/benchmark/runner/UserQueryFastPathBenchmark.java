package io.skis.benchmark.runner;

import io.skis.benchmark.jdbc.JdbcUser;
import io.skis.benchmark.skis.SkisUser;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/** Guards the bounded no-predicate and single-equality entity query Fast Paths. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@Threads(1)
public class UserQueryFastPathBenchmark {

  @Benchmark
  public List<JdbcUser> jdbcAll(JdbcUserBenchmarkState state) {
    return state.findAll();
  }

  @Benchmark
  public List<SkisUser> skisAll(SkisUserBenchmarkState state) {
    return state.findAll();
  }

  @Benchmark
  public List<JdbcUser> jdbcEquality(JdbcUserBenchmarkState state) {
    return state.findByUsername();
  }

  @Benchmark
  public List<SkisUser> skisEquality(SkisUserBenchmarkState state) {
    return state.findByUsername();
  }
}
