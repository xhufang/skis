package io.skis.sql.ast;

import java.util.Objects;

/**
 * Validated, unquoted SQL identifier stored in the AST.
 *
 * <p>Identifiers use a deliberately portable ASCII form. A renderer is still responsible for
 * applying the quoting rules of its database dialect.
 */
public record Identifier(String value) {

  /** Creates a validated identifier. */
  public Identifier {
    Objects.requireNonNull(value, "value");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("SQL identifier must not be empty");
    }
    if (!isInitialCharacter(value.charAt(0))) {
      throw invalid(value);
    }
    for (int index = 1; index < value.length(); index++) {
      if (!isSubsequentCharacter(value.charAt(index))) {
        throw invalid(value);
      }
    }
  }

  /** Creates a validated identifier from user-facing DSL input. */
  public static Identifier of(String value) {
    return new Identifier(value);
  }

  private static boolean isInitialCharacter(char character) {
    return character == '_'
        || character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z';
  }

  private static boolean isSubsequentCharacter(char character) {
    return isInitialCharacter(character) || character >= '0' && character <= '9';
  }

  private static IllegalArgumentException invalid(String value) {
    return new IllegalArgumentException(
        "SQL identifier '" + value + "' must match [A-Za-z_][A-Za-z0-9_]*");
  }
}
