package io.skis.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Test-only environment. No static initialization starts Docker or reads environment settings. */
public final class PostgresEnvironment implements AutoCloseable {

  private final PostgresSettings settings;
  private final Optional<PostgreSQLContainer> container;

  private PostgresEnvironment(PostgresSettings settings, Optional<PostgreSQLContainer> container) {
    this.settings = settings;
    this.container = container;
  }

  public static PostgresEnvironment open() {
    return open(System.getenv());
  }

  static PostgresEnvironment open(Map<String, String> environment) {
    Optional<PostgresSettings> explicit = PostgresSettings.fromEnvironment(environment);
    if (explicit.isPresent()) {
      PostgresEnvironment result = new PostgresEnvironment(explicit.orElseThrow(), Optional.empty());
      result.verify("external-url", "caller-managed");
      return result;
    }
    String image = lockedImage();
    PostgreSQLContainer owned = new PostgreSQLContainer(image)
        .withDatabaseName("skis").withUsername("skis")
        .withPassword(UUID.randomUUID().toString())
        .withLabel("io.skis.postgres-contract", "true");
    try {
      owned.start();
      PostgresEnvironment result = new PostgresEnvironment(
          new PostgresSettings(owned.getJdbcUrl(), owned.getUsername(), owned.getPassword()),
          Optional.of(owned));
      result.verify("testcontainers", image);
      return result;
    } catch (RuntimeException | Error failure) {
      try {
        owned.stop();
      } catch (RuntimeException cleanup) {
        // Keep container internals and credentials out of reports.
        throw new IllegalStateException("PostgreSQL environment startup and container cleanup failed");
      }
      throw new IllegalStateException("PostgreSQL environment startup failed; Docker and locked image are required");
    }
  }

  static String lockedImage() {
    try (InputStream input = PostgresEnvironment.class.getResourceAsStream("postgresql-image.json")) {
      if (input == null) {
        throw new IllegalStateException("Missing PostgreSQL image lock resource");
      }
      String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      Matcher match = Pattern.compile(
          "\"image\"\\s*:\\s*\"(postgres:16\\.15-bookworm@sha256:[a-f0-9]{64})\"").matcher(json);
      if (!match.find()) {
        throw new IllegalStateException("Invalid PostgreSQL image lock resource");
      }
      String locked = Objects.requireNonNull(match.group(1), "locked image");
      // Testcontainers 2.0.5 parses tag@digest as a repository containing a colon.
      // Use the equivalent digest-only reference; the JSON retains the verified tag association.
      return "postgres" + locked.substring(locked.indexOf('@'));
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot read PostgreSQL image lock resource");
    }
  }

  private void verify(String source, String image) {
    try (Connection connection = dataSource(settings).getConnection();
        Statement statement = connection.createStatement();
        ResultSet version = statement.executeQuery("SHOW server_version")) {
      if (!version.next() || connection.getMetaData().getDatabaseMajorVersion() != 16) {
        throw new IllegalStateException("PostgreSQL environment requires server major version 16");
      }
      System.out.println("PostgreSQL environment: source=" + source + ", version="
          + version.getString(1) + ", image=" + image);
    } catch (SQLException failure) {
      throw databaseFailure("connection/version probe", failure);
    }
  }

  /** Fixed generated schemas remain unchanged inside an independently owned database per suite. */
  public Database database() {
    String name = "skis_contract_" + UUID.randomUUID().toString().replace("-", "");
    boolean created = false;
    try (Connection connection = dataSource(settings).getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE \"" + name + "\"");
      created = true;
    } catch (SQLException failure) {
      if (created) {
        drop(name);
      }
      throw databaseFailure("isolated database creation (test account requires CREATEDB)", failure);
    }
    try {
      PGSimpleDataSource target = dataSource(settings);
      target.setDatabaseName(name);
      // No caller connection options may redirect the new database.
      return new Database(name, target);
    } catch (RuntimeException | Error failure) {
      drop(name);
      throw failure;
    }
  }

  private static PGSimpleDataSource dataSource(PostgresSettings settings) {
    PGSimpleDataSource result = new PGSimpleDataSource();
    result.setURL(settings.url);
    result.setUser(settings.user);
    result.setPassword(settings.password);
    result.setLoginTimeout(15);
    result.setConnectTimeout(15);
    result.setSocketTimeout(30);
    return result;
  }

  private void drop(String name) {
    try (Connection connection = dataSource(settings).getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS \"" + name + "\" WITH (FORCE)");
    } catch (SQLException failure) {
      throw databaseFailure("isolated database cleanup", failure);
    }
  }

  private static IllegalStateException databaseFailure(String phase, SQLException failure) {
    return new IllegalStateException("PostgreSQL environment " + phase
        + " failed (SQLState=" + failure.getSQLState() + "); URL and credentials withheld");
  }

  @Override
  public void close() {
    container.ifPresent(PostgreSQLContainer::stop);
  }

  public final class Database implements AutoCloseable {
    private final String name;
    private final PGSimpleDataSource dataSource;
    private boolean closed;

    private Database(String name, PGSimpleDataSource dataSource) {
      this.name = name;
      this.dataSource = dataSource;
    }

    public PGSimpleDataSource dataSource() {
      return dataSource;
    }

    void configure(ProcessBuilder child) {
      child.environment().put("SKIS_POSTGRES_URL", dataSource.getURL());
      child.environment().put("SKIS_POSTGRES_USER", settings.user);
      child.environment().put("SKIS_POSTGRES_PASSWORD", settings.password);
    }

    @Override
    public void close() {
      if (!closed) {
        drop(name);
        closed = true;
      }
    }
  }
}
