package io.skis.core.internal;

import io.skis.core.SqlLoggingOptions;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementStartedEvent;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Value-free logging adapter; an operation captures its demand once for paired lifecycles. */
final class SqlLoggingListener implements ExecutionListener {

  static final int SQL_DEMAND = 1;
  static final int SLOW_DEMAND = 2;

  private static final Logger SQL = LoggerFactory.getLogger("io.skis.sql");
  private static final Logger SLOW = LoggerFactory.getLogger("io.skis.sql.slow");

  private final boolean sql;
  private final long thresholdNanos;
  private final boolean snapshot;

  SqlLoggingListener(SqlLoggingOptions options) {
    sql = options.sql();
    Duration threshold = options.slowQueryThreshold();
    long nanos = -1L;
    if (threshold != null) {
      try {
        nanos = threshold.toNanos();
      } catch (ArithmeticException overflow) {
        nanos = Long.MAX_VALUE;
      }
    }
    thresholdNanos = nanos;
    snapshot = false;
  }

  private SqlLoggingListener(boolean sql, long thresholdNanos) {
    this.sql = sql;
    this.thresholdNanos = thresholdNanos;
    snapshot = true;
  }

  boolean enabled() {
    return snapshot
        ? sql || thresholdNanos >= 0L
        : (sql && SafeLog.debugEnabled(SQL))
            || (thresholdNanos >= 0L && SafeLog.warnEnabled(SLOW));
  }

  int demand() {
    return (sql && SafeLog.debugEnabled(SQL) ? SQL_DEMAND : 0)
        | (thresholdNanos >= 0L && SafeLog.warnEnabled(SLOW) ? SLOW_DEMAND : 0);
  }

  SqlLoggingListener snapshot(int demand) {
    return new SqlLoggingListener(
        sql && (demand & SQL_DEMAND) != 0,
        (demand & SLOW_DEMAND) != 0 ? thresholdNanos : -1L);
  }

  @Override
  public void statementStarted(StatementStartedEvent event) {
    if (sql) {
      SafeLog.debug(SQL,
          "SQL started [operation=" + event.operationId() + ", statement=" + event.statementId()
              + ", purpose=" + event.purpose() + ", fingerprint=" + SafeLog.text(event.sqlFingerprint(), 128)
              + ", sql=" + SafeLog.text(event.sqlTemplate(), 4096) + "]");
    }
  }

  @Override
  public void statementCompleted(StatementCompletedEvent event) {
    if (!sql && (thresholdNanos < 0L || event.durationNanos() < thresholdNanos)) {
      return;
    }
    String message = "SQL completed [operation=" + event.operationId()
        + ", statement=" + event.statementId() + ", purpose=" + event.purpose()
        + ", fingerprint=" + SafeLog.text(event.sqlFingerprint(), 128)
        + ", outcome=" + event.outcome() + ", termination=" + event.termination()
        + ", durationNanos=" + event.durationNanos() + ", failureStage=" + event.failureStage() + "]";
    if (sql) {
      SafeLog.debug(SQL, message);
    }
    if (thresholdNanos >= 0L && event.durationNanos() >= thresholdNanos) {
      SafeLog.warn(SLOW, message + " sql=" + SafeLog.text(event.sqlTemplate(), 4096));
    }
  }
}
