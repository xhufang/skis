package io.skis.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.skis.annotations.PropertyFrom;
import io.skis.annotations.ReusableProjection;
import io.skis.annotations.SpecialProjection;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkisProjectionIncrementalContractTest {

  @Test
  void keepsProjectionAnnotationsVisibleToIncrementalBuildTools() {
    assertClassRetention(PropertyFrom.class);
    assertClassRetention(ReusableProjection.class);
    assertClassRetention(SpecialProjection.class);
  }

  @Test
  void declaresTheCrossEntityProjectionProcessorAsAggregating() throws IOException {
    String resourceName = "/META-INF/gradle/incremental.annotation.processors";
    try (var input = SkisProjectionProcessor.class.getResourceAsStream(resourceName)) {
      assertNotNull(input, "missing " + resourceName);
      List<String> declarations =
          new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
      assertEquals(
          1,
          declarations.stream()
              .filter(
                  declaration ->
                      declaration.equals(
                          SkisProjectionProcessor.class.getName() + ",aggregating"))
              .count());
    }
  }

  private static void assertClassRetention(Class<?> annotationType) {
    Retention retention = annotationType.getAnnotation(Retention.class);
    assertNotNull(retention, annotationType.getName());
    assertEquals(RetentionPolicy.CLASS, retention.value(), annotationType.getName());
  }
}
