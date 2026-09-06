package io.skis.query;

import java.lang.reflect.Array;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import org.jspecify.annotations.Nullable;

/** Captures the mutable value representations supported by the built-in JDBC codecs. */
final class QueryValueSnapshots {

  private QueryValueSnapshots() {}

  @SuppressWarnings("unchecked")
  static <V> @Nullable V capture(V value) {
    return (V) copy(value);
  }

  static @Nullable Object copy(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    if (value.getClass().isArray()) {
      return copyArray(value);
    }
    return switch (value) {
      case Timestamp timestamp -> timestamp.clone();
      case Time time -> time.clone();
      case Date date -> date.clone();
      default -> value;
    };
  }

  private static Object copyArray(Object value) {
    int length = Array.getLength(value);
    Object copy = Array.newInstance(value.getClass().getComponentType(), length);
    for (int index = 0; index < length; index++) {
      Array.set(copy, index, copy(Array.get(value, index)));
    }
    return copy;
  }
}
