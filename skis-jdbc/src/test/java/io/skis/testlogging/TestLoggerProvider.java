package io.skis.testlogging;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/** Test-only backend: accepts safe strings and can fail level probes or actual output. */
@org.jspecify.annotations.NullUnmarked
public final class TestLoggerProvider implements SLF4JServiceProvider {
  public static final Set<String> DEBUG = ConcurrentHashMap.newKeySet();
  public static final Set<String> WARN = ConcurrentHashMap.newKeySet();
  public static final List<Entry> EVENTS = new CopyOnWriteArrayList<>();
  public static volatile int failure;
  public static volatile boolean failProbe;
  public static volatile BiConsumer<String, Level> onProbe;
  public static volatile Runnable onLog;

  public static void reset() {
    DEBUG.clear();
    WARN.clear();
    EVENTS.clear();
    failure = 0;
    failProbe = false;
    onProbe = null;
    onLog = null;
  }

  public record Entry(String logger, Level level, String message) {}

  @Override public ILoggerFactory getLoggerFactory() { return TestLogger::new; }
  @Override public IMarkerFactory getMarkerFactory() { return new BasicMarkerFactory(); }
  @Override public MDCAdapter getMDCAdapter() { return new BasicMDCAdapter(); }
  @Override public String getRequestedApiVersion() { return "2.0.99"; }
  @Override public void initialize() {}

  private static final class TestLogger extends AbstractLogger {
    private static final long serialVersionUID = 1L;

    private TestLogger(String loggerName) { name = loggerName; }
    private boolean enabled(Set<String> levels, Level level) {
      BiConsumer<String, Level> callback = onProbe;
      if (callback != null) callback.accept(name, level);
      if (failProbe) throw new IllegalStateException("secret backend probe");
      return levels.contains(name);
    }
    @Override public boolean isTraceEnabled() { return false; }
    @Override public boolean isTraceEnabled(Marker marker) { return false; }
    @Override public boolean isDebugEnabled() { return enabled(DEBUG, Level.DEBUG); }
    @Override public boolean isDebugEnabled(Marker marker) { return isDebugEnabled(); }
    @Override public boolean isInfoEnabled() { return false; }
    @Override public boolean isInfoEnabled(Marker marker) { return false; }
    @Override public boolean isWarnEnabled() { return enabled(WARN, Level.WARN); }
    @Override public boolean isWarnEnabled(Marker marker) { return isWarnEnabled(); }
    @Override public boolean isErrorEnabled() { return false; }
    @Override public boolean isErrorEnabled(Marker marker) { return false; }
    @Override protected String getFullyQualifiedCallerName() { return TestLogger.class.getName(); }
    @Override protected void handleNormalizedLoggingCall(
        Level level, Marker marker, String message, Object[] arguments, Throwable throwable) {
      if (throwable != null || (arguments != null && arguments.length != 0)) {
        throw new AssertionError("framework handed objects or a Throwable to the logger");
      }
      Runnable callback = onLog;
      if (callback != null) callback.run();
      if (failure == 1) throw new IllegalStateException("secret backend output");
      if (failure == 2) throw new AssertionError("fatal backend output");
      EVENTS.add(new Entry(name, level, message));
    }
  }
}
