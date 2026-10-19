package io.skis.dialect.support;

import io.skis.dialect.spi.ParameterLayout;
import java.util.Objects;

/**
 * Immutable result of the safe SQL compilation facade.
 *
 * <p>The template contains only the final SQL text and its value-independent parameter layout. It
 * never stores invocation parameter values, analysis state, a connection, or an execution resource.
 */
public record SqlTemplate(String sql, ParameterLayout parameterLayout) {

  /** Validates that every final placeholder has exactly one laid-out position. */
  public SqlTemplate {
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(parameterLayout, "parameterLayout");
    if (sql.isBlank()) {
      throw new IllegalArgumentException("compiled SQL must not be blank");
    }
    int placeholders = countPlaceholders(sql);
    if (placeholders != parameterLayout.size()) {
      throw new IllegalArgumentException(
          "compiled SQL has "
              + placeholders
              + " placeholders but the final parameter layout has "
              + parameterLayout.size()
              + " positions");
    }
  }

  /** Number of ordered parameter positions in the final SQL. */
  public int parameterCount() {
    return parameterLayout.size();
  }

  private static int countPlaceholders(String sql) {
    int count = 0;
    char quote = 0;
    for (int index = 0; index < sql.length(); index++) {
      char current = sql.charAt(index);
      if (quote != 0) {
        if (current == quote) {
          if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
            index++;
          } else {
            quote = 0;
          }
        }
        continue;
      }
      if (current == '\'' || current == '"') {
        quote = current;
      } else if (current == '?') {
        count++;
      }
    }
    return count;
  }
}
