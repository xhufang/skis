package io.skis.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

class PostgresSettingsTest {
  @Test
  void onlyAbsentUrlSelectsContainerAndDoesNotValidateStrayCredentials() {
    assertTrue(PostgresSettings.fromEnvironment(Map.of()).isEmpty());
    assertTrue(PostgresSettings.fromEnvironment(Map.of("SKIS_POSTGRES_USER", "")).isEmpty());
  }

  @Test
  void invalidExplicitConfigurationFailsBeforeAnyDockerAccessWithoutEchoingValues() {
    for (String url : new String[] {"", " ", "jdbc:h2:mem:secret", "jdbc:postgresql://x:bad/db",
        "jdbc:postgresql://localhost/db?password=secret"}) {
      IllegalStateException failure = assertThrows(IllegalStateException.class,
          () -> PostgresEnvironment.open(Map.of("SKIS_POSTGRES_URL", url)));
      assertFalse(failure.toString().contains("secret"));
    }
    for (String missing : new String[] {"SKIS_POSTGRES_USER", "SKIS_POSTGRES_PASSWORD"}) {
      Map<String, String> variables = new HashMap<>(Map.of("SKIS_POSTGRES_URL",
          "jdbc:postgresql://localhost/skis", "SKIS_POSTGRES_USER", "user",
          "SKIS_POSTGRES_PASSWORD", "secret"));
      variables.remove(missing);
      assertThrows(IllegalStateException.class, () -> PostgresEnvironment.open(variables));
      variables.put(missing, " ");
      assertThrows(IllegalStateException.class, () -> PostgresEnvironment.open(variables));
    }
  }

  @Test
  void preservesExplicitConfigurationAndDoesNotExposeCredentialsInToString() {
    PostgresSettings settings = PostgresSettings.fromEnvironment(Map.of(
        "SKIS_POSTGRES_URL", "jdbc:postgresql://localhost/skis",
        "SKIS_POSTGRES_USER", "test-user", "SKIS_POSTGRES_PASSWORD", "test-secret")).orElseThrow();
    assertEquals("test-user", settings.user);
    assertEquals("test-secret", settings.password);
    assertFalse(settings.toString().contains("test-secret"));
    assertFalse(settings.toString().contains("localhost"));
    assertTrue(PostgresEnvironment.lockedImage().matches(
        "postgres@sha256:[a-f0-9]{64}"));
    DockerImageName image = DockerImageName.parse(PostgresEnvironment.lockedImage());
    image.assertValid();
    image.assertCompatibleWith(DockerImageName.parse("postgres"));
  }
}
