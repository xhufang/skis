package io.skis.query.internal;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import org.jspecify.annotations.Nullable;

/** Captures mutable built-in JDBC values exactly once, retaining runtime array component types. */
final class QueryValueSnapshots {
  private QueryValueSnapshots() {}

  @SuppressWarnings("unchecked")
  static <V> @Nullable V capture(V value) {
    return (V) copy(value);
  }

  static @Nullable Object copy(@Nullable Object value) {
    return switch (value) {
      case null -> null;
      case boolean[] array -> array.clone();
      case byte[] array -> array.clone();
      case short[] array -> array.clone();
      case char[] array -> array.clone();
      case int[] array -> array.clone();
      case long[] array -> array.clone();
      case float[] array -> array.clone();
      case double[] array -> array.clone();
      case @Nullable Object[] array -> copyArray(array);
      case Timestamp timestamp -> timestamp.clone();
      case Time time -> time.clone();
      case Date date -> date.clone();
      default -> value;
    };
  }

  private static @Nullable Object[] copyArray(@Nullable Object[] values) {
    @Nullable Object[] snapshot = values.clone();
    for (int index = 0; index < snapshot.length; index++) {
      snapshot[index] = copy(snapshot[index]);
    }
    return snapshot;
  }
}
