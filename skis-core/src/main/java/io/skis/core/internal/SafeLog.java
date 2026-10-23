package io.skis.core.internal;

import org.slf4j.Logger;

/** Safe string-only outlets. Ordinary backend failures never replace execution or cleanup. */
public final class SafeLog {

  private static final String TRUNCATED = "[truncated]";

  private SafeLog() {}

  public static boolean debugEnabled(Logger logger) {
    boolean entered = ExecutionListenerDispatcher.enterCallback();
    try {
      return logger.isDebugEnabled();
    } catch (RuntimeException ignored) {
      return false;
    } finally {
      ExecutionListenerDispatcher.exitCallback(entered);
    }
  }

  public static boolean warnEnabled(Logger logger) {
    boolean entered = ExecutionListenerDispatcher.enterCallback();
    try {
      return logger.isWarnEnabled();
    } catch (RuntimeException ignored) {
      return false;
    } finally {
      ExecutionListenerDispatcher.exitCallback(entered);
    }
  }

  public static void debug(Logger logger, String safeMessage) {
    boolean entered = ExecutionListenerDispatcher.enterCallback();
    try {
      logger.debug(safeMessage);
    } catch (RuntimeException ignored) {
      // Never log a logging failure, or hand a Throwable to the backend.
    } finally {
      ExecutionListenerDispatcher.exitCallback(entered);
    }
  }

  public static void warn(Logger logger, String safeMessage) {
    boolean entered = ExecutionListenerDispatcher.enterCallback();
    try {
      logger.warn(safeMessage);
    } catch (RuntimeException ignored) {
      // Fatal Errors deliberately propagate through the caller's existing cleanup path.
    } finally {
      ExecutionListenerDispatcher.exitCallback(entered);
    }
  }

  /** Escapes controls before applying a code-point budget; never splits a surrogate pair. */
  public static String text(String value, int maximumCodePoints) {
    StringBuilder result = null;
    int count = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      int width = Character.charCount(codePoint);
      String escaped = null;
      if (Character.isISOControl(codePoint)
          || Character.getType(codePoint) == Character.FORMAT
          || codePoint == 0x2028
          || codePoint == 0x2029
          || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
        escaped = switch (codePoint) {
          case '\n' -> "\\n";
          case '\r' -> "\\r";
          case '\t' -> "\\t";
          default -> codePoint <= 0xFFFF
              ? "\\u" + Integer.toHexString(codePoint | 0x10000).substring(1)
              : "\\u{" + Integer.toHexString(codePoint) + "}";
        };
      }
      int added = escaped == null ? 1 : escaped.length();
      if (count + added > maximumCodePoints) {
        if (result == null) {
          result = new StringBuilder(Math.min(value.length(), maximumCodePoints));
          result.append(value, 0, offset);
        }
        int keep = Math.max(0, maximumCodePoints - TRUNCATED.length());
        if (count > keep) {
          result.setLength(result.offsetByCodePoints(0, keep));
        }
        result.append(TRUNCATED, 0, Math.min(maximumCodePoints, TRUNCATED.length()));
        break;
      }
      if (escaped != null) {
        if (result == null) {
          result = new StringBuilder(Math.min(value.length(), maximumCodePoints));
          result.append(value, 0, offset);
        }
        result.append(escaped);
      } else if (result != null) {
        result.appendCodePoint(codePoint);
      }
      count += added;
      offset += width;
    }
    return result == null ? value : result.toString();
  }
}
