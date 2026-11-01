package io.skis.jdbc.internal;

/** Conservative lexical single-statement check, performed only when a plan is assembled. */
final class CursorSql {

  private CursorSql() {}

  static boolean isSingleStatement(String sql) {
    boolean content = false;
    boolean terminated = false;
    for (int i = 0; i < sql.length(); ) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (sql.startsWith("--", i)) {
        i += 2;
        while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
      } else if (sql.startsWith("/*", i)) {
        int depth = 1;
        i += 2;
        while (i < sql.length() && depth > 0) {
          if (sql.startsWith("/*", i)) {
            depth++;
            i += 2;
          } else if (sql.startsWith("*/", i)) {
            depth--;
            i += 2;
          } else {
            i++;
          }
        }
        if (depth != 0) return false;
      } else if (c == ';') {
        if (!content || terminated) return false;
        terminated = true;
        i++;
      } else {
        if (terminated) return false;
        content = true;
        if (c == '\'' || c == '"') {
          boolean closed = false;
          i++;
          while (i < sql.length()) {
            char next = sql.charAt(i++);
            // Backslash string semantics depend on driver/server settings. Do not guess.
            if (c == '\'' && next == '\\') return false;
            if (next == c) {
              if (i < sql.length() && sql.charAt(i) == c) {
                i++;
              } else {
                closed = true;
                break;
              }
            }
          }
          if (!closed) return false;
        } else if (c == '$') {
          int tagEnd = i + 1;
          while (tagEnd < sql.length()
              && (sql.charAt(tagEnd) == '_' || Character.isLetterOrDigit(sql.charAt(tagEnd)))) {
            tagEnd++;
          }
          if (tagEnd < sql.length() && sql.charAt(tagEnd) == '$') {
            String delimiter = sql.substring(i, tagEnd + 1);
            int end = sql.indexOf(delimiter, tagEnd + 1);
            if (end < 0) return false;
            i = end + delimiter.length();
          } else {
            i++;
          }
        } else {
          i++;
        }
      }
    }
    return content;
  }
}
