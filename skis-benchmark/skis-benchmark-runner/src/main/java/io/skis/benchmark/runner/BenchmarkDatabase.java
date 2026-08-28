package io.skis.benchmark.runner;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Creates identically configured data sources for every benchmark implementation. */
final class BenchmarkDatabase {

  private static final String DEFAULT_JDBC_URL =
      "jdbc:postgresql://localhost:5432/xhu"
          + "?currentSchema=skis&stringtype=unspecified&lowercase=true";
  private static final String DEFAULT_USERNAME = "postgres";
  private static final int DEFAULT_POOL_SIZE = 4;

  private BenchmarkDatabase() {}

  static HikariDataSource openDataSource(String benchmarkName) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(environment("SKIS_BENCHMARK_JDBC_URL", DEFAULT_JDBC_URL));
    config.setUsername(environment("SKIS_BENCHMARK_DB_USERNAME", DEFAULT_USERNAME));
    config.setPassword(requiredEnvironment());
    int poolSize = positiveIntegerEnvironment();
    config.setMinimumIdle(poolSize);
    config.setMaximumPoolSize(poolSize);
    config.setAutoCommit(true);
    config.setPoolName("skis-benchmark-" + benchmarkName);
    return new HikariDataSource(config);
  }

  private static String environment(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static String requiredEnvironment() {
    String value = System.getenv("SKIS_BENCHMARK_DB_PASSWORD");
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Required environment variable is not set: " + "SKIS_BENCHMARK_DB_PASSWORD");
    }
    return value;
  }

  private static int positiveIntegerEnvironment() {
    String value = System.getenv("SKIS_BENCHMARK_POOL_SIZE");
    if (value == null || value.isBlank()) {
      return BenchmarkDatabase.DEFAULT_POOL_SIZE;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 1) {
        throw new IllegalArgumentException("must be greater than zero");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Environment variable " + "SKIS_BENCHMARK_POOL_SIZE" + " must be a positive integer",
          exception);
    }
  }
}
