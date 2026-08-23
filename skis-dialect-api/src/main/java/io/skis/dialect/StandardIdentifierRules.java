package io.skis.dialect;

import java.util.Objects;

/** SQL-standard double-quote identifier rules used by PostgreSQL and H2. */
public final class StandardIdentifierRules implements IdentifierRules {

  /** Stateless shared instance. */
  public static final StandardIdentifierRules INSTANCE = new StandardIdentifierRules();

  private StandardIdentifierRules() {}

  @Override
  public String quote(String identifier) {
    Objects.requireNonNull(identifier, "identifier");
    if (identifier.isBlank()) {
      throw new IllegalArgumentException("SQL identifier must not be blank");
    }
    if (identifier.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("SQL identifier must not contain NUL");
    }
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
