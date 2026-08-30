package io.skis.core;

import java.util.Objects;

/** A short, validated diagnostic label that can be attached to one JDBC statement. */
public final class QueryTag {

  /** Maximum number of ASCII characters accepted in one tag. */
  public static final int MAX_LENGTH = 128;

  private final String value;

  private QueryTag(String value) {
    this.value = value;
  }

  /**
   * Creates a validated tag.
   *
   * <p>Tags may contain ASCII letters, digits, spaces and {@code . _ : / -}. This deliberately
   * excludes SQL comment delimiters, statement separators, quotes, escapes and control characters.
   */
  public static QueryTag of(String value) {
    Objects.requireNonNull(value, "value");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("query tag must not be empty");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("query tag must not exceed " + MAX_LENGTH + " characters");
    }
    if (value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ') {
      throw new IllegalArgumentException("query tag must not start or end with a space");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!allowed(character)) {
        throw new IllegalArgumentException(
            "query tag contains an unsupported character at index " + index);
      }
    }
    return new QueryTag(value);
  }

  /** Returns the validated label without SQL comment delimiters. */
  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof QueryTag that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }

  private static boolean allowed(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character >= '0' && character <= '9'
        || character == ' '
        || character == '.'
        || character == '_'
        || character == ':'
        || character == '/'
        || character == '-';
  }
}
