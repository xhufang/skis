package io.skis.testsupport;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.postgresql.Driver;

/** Validates explicit configuration before any Docker or connection access. */
final class PostgresSettings {

  final String url;
  final String user;
  final String password;

  PostgresSettings(String url, String user, String password) {
    this.url = url;
    this.user = user;
    this.password = password;
  }

  static Optional<PostgresSettings> fromEnvironment(Map<String, String> environment) {
    String url = environment.get("SKIS_POSTGRES_URL");
    if (url == null) {
      return Optional.empty();
    }
    if (url.isBlank() || !url.startsWith("jdbc:postgresql:")
        || url.chars().anyMatch(Character::isWhitespace)) {
      throw invalid("SKIS_POSTGRES_URL must be a nonblank PostgreSQL JDBC URL");
    }
    // Reject embedded credentials before PgJDBC can log malformed URL parsing failures.
    String decoded;
    try {
      decoded = URLDecoder.decode(url, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException failure) {
      throw invalid("Malformed SKIS_POSTGRES_URL encoding");
    }
    if (decoded.matches(".*[?&](user|password)=.*") || decoded.contains("@")) {
      throw invalid("Supply credentials only through environment variables");
    }
    Properties parsed = Driver.parseURL(url, new Properties());
    if (parsed == null || parsed.containsKey("user") || parsed.containsKey("password")) {
      throw invalid("Invalid SKIS_POSTGRES_URL; supply credentials only through environment variables");
    }
    return Optional.of(new PostgresSettings(url,
        required(environment, "SKIS_POSTGRES_USER"),
        required(environment, "SKIS_POSTGRES_PASSWORD")));
  }

  private static String required(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      throw invalid(name + " is required for an explicit PostgreSQL URL");
    }
    return value;
  }

  private static IllegalStateException invalid(String message) {
    return new IllegalStateException("PostgreSQL environment configuration: " + message);
  }

  @Override
  public String toString() {
    return "PostgresSettings[credentials and URL withheld]";
  }
}
