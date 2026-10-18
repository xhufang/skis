package io.skis.query.spi;

import io.skis.metadata.PropertyMeta;
import java.util.Objects;
import java.util.Optional;

/** Immutable, query-independent source template for one special-projection value. */
public record ProjectionSource(PropertyMeta<?, ?> property, Optional<String> alias) {

  /** Creates a validated source template. */
  public ProjectionSource {
    Objects.requireNonNull(property, "property");
    Objects.requireNonNull(alias, "alias");
    alias.ifPresent(ProjectionSource::requireAlias);
  }

  /** Creates a source that auto-binds when its declaring entity has one visible occurrence. */
  public static ProjectionSource of(PropertyMeta<?, ?> property) {
    return new ProjectionSource(property, Optional.empty());
  }

  /** Creates a source that binds to the visible occurrence with the exact alias. */
  public static ProjectionSource of(PropertyMeta<?, ?> property, String alias) {
    return new ProjectionSource(property, Optional.of(requireAlias(alias)));
  }

  private static String requireAlias(String alias) {
    Objects.requireNonNull(alias, "alias");
    if (alias.isEmpty() || !isInitialCharacter(alias.charAt(0))) {
      throw invalidAlias(alias);
    }
    for (int index = 1; index < alias.length(); index++) {
      if (!isSubsequentCharacter(alias.charAt(index))) {
        throw invalidAlias(alias);
      }
    }
    return alias;
  }

  private static boolean isInitialCharacter(char character) {
    return character == '_'
        || character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z';
  }

  private static boolean isSubsequentCharacter(char character) {
    return isInitialCharacter(character) || character >= '0' && character <= '9';
  }

  private static IllegalArgumentException invalidAlias(String alias) {
    return new IllegalArgumentException(
        "projection source alias '" + alias + "' must match [A-Za-z_][A-Za-z0-9_]*");
  }
}
