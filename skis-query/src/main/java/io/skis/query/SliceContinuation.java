package io.skis.query;

import io.skis.metadata.GeneratedModelAbi;
import io.skis.sql.ast.SqlType;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Opaque, immutable continuation that can only be resumed by a compatible query. */
public final class SliceContinuation {

  static final int FORMAT_VERSION = 1;

  enum Mode {
    OFFSET,
    KEYSET
  }

  private final int formatVersion;
  private final Mode mode;
  private final String queryFingerprint;
  private final String orderSignature;
  private final List<SqlType> sqlTypes;
  private final List<Boolean> nullMarkers;
  private final List<@Nullable Object> keysetValues;
  private final long nextOffset;
  private final String parameterDigest;
  private final int generatedAbi;

  private SliceContinuation(
      Mode mode,
      String queryFingerprint,
      String orderSignature,
      List<SqlType> sqlTypes,
      List<Boolean> nullMarkers,
      List<@Nullable Object> keysetValues,
      long nextOffset,
      String parameterDigest) {
    this.formatVersion = FORMAT_VERSION;
    this.mode = Objects.requireNonNull(mode, "mode");
    this.queryFingerprint = requireText(queryFingerprint, "queryFingerprint");
    this.orderSignature = requireText(orderSignature, "orderSignature");
    this.sqlTypes = List.copyOf(sqlTypes);
    this.nullMarkers = List.copyOf(nullMarkers);
    this.keysetValues = immutableValues(keysetValues);
    this.nextOffset = nextOffset;
    this.parameterDigest = requireText(parameterDigest, "parameterDigest");
    this.generatedAbi = GeneratedModelAbi.CURRENT;
    if (this.sqlTypes.size() != this.nullMarkers.size()
        || this.sqlTypes.size() != this.keysetValues.size()) {
      throw new IllegalArgumentException("continuation keyset shapes differ");
    }
  }

  static SliceContinuation offset(
      String queryFingerprint, String orderSignature, long nextOffset, String parameterDigest) {
    if (nextOffset < 0) {
      throw new IllegalArgumentException("nextOffset must not be negative");
    }
    return new SliceContinuation(
        Mode.OFFSET,
        queryFingerprint,
        orderSignature,
        List.of(),
        List.of(),
        List.of(),
        nextOffset,
        parameterDigest);
  }

  static SliceContinuation keyset(
      String queryFingerprint,
      String orderSignature,
      List<SqlType> sqlTypes,
      List<Boolean> nullMarkers,
      List<@Nullable Object> values,
      String parameterDigest) {
    return new SliceContinuation(
        Mode.KEYSET,
        queryFingerprint,
        orderSignature,
        sqlTypes,
        nullMarkers,
        values,
        0,
        parameterDigest);
  }

  int formatVersion() {
    return formatVersion;
  }

  Mode mode() {
    return mode;
  }

  String queryFingerprint() {
    return queryFingerprint;
  }

  String orderSignature() {
    return orderSignature;
  }

  List<SqlType> sqlTypes() {
    return sqlTypes;
  }

  List<Boolean> nullMarkers() {
    return nullMarkers;
  }

  List<@Nullable Object> keysetValues() {
    return immutableValues(keysetValues);
  }

  long nextOffset() {
    return nextOffset;
  }

  String parameterDigest() {
    return parameterDigest;
  }

  int generatedAbi() {
    return generatedAbi;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof SliceContinuation continuation
            && formatVersion == continuation.formatVersion
            && nextOffset == continuation.nextOffset
            && generatedAbi == continuation.generatedAbi
            && mode == continuation.mode
            && queryFingerprint.equals(continuation.queryFingerprint)
            && orderSignature.equals(continuation.orderSignature)
            && sqlTypes.equals(continuation.sqlTypes)
            && nullMarkers.equals(continuation.nullMarkers)
            && valuesEqual(keysetValues, continuation.keysetValues)
            && parameterDigest.equals(continuation.parameterDigest);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            formatVersion,
            mode,
            queryFingerprint,
            orderSignature,
            sqlTypes,
            nullMarkers,
            nextOffset,
            parameterDigest,
            generatedAbi);
    for (Object keysetValue : keysetValues) {
      result = 31 * result + deepValueHash(keysetValue);
    }
    return result;
  }

  @Override
  public String toString() {
    return "SliceContinuation[version=" + formatVersion + ", mode=" + mode + ", values=<redacted>]";
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static List<@Nullable Object> immutableValues(List<@Nullable Object> values) {
    Objects.requireNonNull(values, "keysetValues");
    List<@Nullable Object> copy = new ArrayList<>(values.size());
    values.forEach(value -> copy.add(copyArray(value)));
    return Collections.unmodifiableList(copy);
  }

  private static boolean valuesEqual(List<@Nullable Object> left, List<@Nullable Object> right) {
    if (left.size() != right.size()) {
      return false;
    }
    for (int index = 0; index < left.size(); index++) {
      if (!Objects.deepEquals(left.get(index), right.get(index))) {
        return false;
      }
    }
    return true;
  }

  private static int deepValueHash(@Nullable Object value) {
    if (value == null) {
      return 0;
    }
    if (!value.getClass().isArray()) {
      return value.hashCode();
    }
    int result = 1;
    for (int index = 0; index < Array.getLength(value); index++) {
      result = 31 * result + deepValueHash(Array.get(value, index));
    }
    return result;
  }

  private static @Nullable Object copyArray(@Nullable Object value) {
    if (value == null || !value.getClass().isArray()) {
      return value;
    }
    int length = Array.getLength(value);
    Object copy = Array.newInstance(value.getClass().getComponentType(), length);
    for (int index = 0; index < length; index++) {
      Array.set(copy, index, copyArray(Array.get(value, index)));
    }
    return copy;
  }
}
