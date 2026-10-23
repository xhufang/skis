package io.skis.jdbc.internal;

import io.skis.core.internal.SafeLog;
import io.skis.core.spi.ParameterRedactor;
import io.skis.mapping.spi.ParameterBindingObserver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** One SQL's bounded string accumulator. Neither fields nor the logger retain bound objects. */
final class ParameterSummary implements ParameterBindingObserver {

  private static final int MAX_VALUE = 128;
  private static final int MAX_STATEMENT = 4096;
  private static final int MAX_SLOTS = 64;
  // Reserve the fingerprint/ID envelope, closing delimiters and a bounded decimal truncation count.
  private static final int CONTENT_BUDGET = MAX_STATEMENT - 256;

  private final ParameterRedactor redactor;
  private final int parameterCount;
  private final boolean timed;
  private final StringBuilder content = new StringBuilder(256);
  private long loggingNanos;
  private int codePoints;
  private int seen;
  private int included;
  private boolean unavailable;
  private boolean full;

  ParameterSummary(ParameterRedactor redactor, int parameterCount, boolean timed) {
    this.redactor = redactor;
    this.parameterCount = parameterCount;
    this.timed = timed;
  }

  @Override
  public boolean needsMore() {
    return !unavailable && !full;
  }

  @Override
  public void bound(int jdbcIndex, Class<?> declaredJavaType, @Nullable Object value) {
    seen++;
    if (!needsMore()) {
      return;
    }
    long startedNanos = timed ? System.nanoTime() : 0L;
    try {
      String safe;
      try {
        String redacted = redactor.redact(jdbcIndex, declaredJavaType, value);
        safe = redacted == null ? "[redaction-failed]" : SafeLog.text(redacted, MAX_VALUE);
      } catch (RuntimeException ignored) {
        safe = "[redaction-failed]";
      }
      String label = (included == 0 ? "" : ", ") + jdbcIndex + "=";
      content.append(label).append(safe);
      codePoints += label.length() + safe.codePointCount(0, safe.length());
      included++;
      full = included == MAX_SLOTS || CONTENT_BUDGET - codePoints < MAX_VALUE + 32;
    } finally {
      if (timed) {
        recordElapsed(startedNanos);
      }
    }
  }

  @Override
  public void unavailable() {
    unavailable = true;
    content.setLength(0);
  }

  void publish(Logger logger, String sqlFingerprint, long operationId, long statementId) {
    long startedNanos = timed ? System.nanoTime() : 0L;
    try {
      String summary;
      if (parameterCount == 0) {
        summary = "";
      } else if (unavailable || seen > parameterCount || (!full && seen != parameterCount)) {
        summary = "[unavailable]";
      } else {
        int omitted = parameterCount - included;
        summary = content + (omitted == 0 ? "" : ", [truncated=" + omitted + "]");
      }
      String correlation = statementId == 0L ? ""
          : ", operation=" + operationId + ", statement=" + statementId;
      SafeLog.debug(logger,
          "SQL parameters [sqlFingerprint=" + sqlFingerprint + correlation
              + ", parameters=[" + summary + "]]");
    } finally {
      content.setLength(0);
      if (timed) {
        recordElapsed(startedNanos);
      }
    }
  }

  long loggingNanos() {
    return loggingNanos;
  }

  private void recordElapsed(long startedNanos) {
    long total = loggingNanos + Math.max(0L, System.nanoTime() - startedNanos);
    loggingNanos = total < 0L ? Long.MAX_VALUE : total;
  }
}
