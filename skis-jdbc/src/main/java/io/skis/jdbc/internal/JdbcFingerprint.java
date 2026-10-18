package io.skis.jdbc.internal;

import java.util.Objects;

/**
 * Framework-internal, value-independent fingerprint shared by safe JDBC diagnostics and executor
 * failure messages.
 *
 * <p>The same algorithm must be used for exception fields and message text so a reported
 * fingerprint cannot disagree with the exception that carries it. This type is not a supported
 * extension surface.
 */
public final class JdbcFingerprint {

  private JdbcFingerprint() {}

  /** Returns the lowercase FNV-1a 64-bit fingerprint of one SQL template. */
  public static String of(String sql) {
    Objects.requireNonNull(sql, "sql");
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < sql.length(); index++) {
      hash ^= sql.charAt(index);
      hash *= 0x100000001b3L;
    }
    return Long.toUnsignedString(hash, 16);
  }
}
