package io.skis.testsupport;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Parent process owns environment and database; consumer receives only connection variables. */
public final class PostgresConsumerLauncher {
  private PostgresConsumerLauncher() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || !"--".equals(args[0])) {
      throw new IllegalArgumentException("Expected -- followed by the consumer Maven command");
    }
    int result;
    try (PostgresEnvironment environment = PostgresEnvironment.open();
        PostgresEnvironment.Database database = environment.database()) {
      ProcessBuilder builder = new ProcessBuilder(Arrays.asList(args).subList(1, args.length));
      database.configure(builder);
      Process child = builder.inheritIO().start();
      try {
        if (!child.waitFor(Duration.ofMinutes(15).toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("Independent PostgreSQL consumer timed out");
        }
        result = child.exitValue();
      } finally {
        try (var descendants = child.descendants()) {
          descendants.forEach(ProcessHandle::destroyForcibly);
        }
        if (child.isAlive()) {
          child.destroyForcibly();
          child.waitFor();
        }
      }
    }
    if (result != 0) {
      throw new IllegalStateException("Independent PostgreSQL consumer failed, exit=" + result);
    }
  }
}
