package io.skis.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeneratedModelAbiTest {

  @Test
  void acceptsTheRuntimeAbi() {
    assertDoesNotThrow(() -> GeneratedModelAbi.requireCompatible(GeneratedModelAbi.CURRENT));
  }

  @Test
  void rejectsGeneratedCodeForAnotherAbi() {
    IncompatibleClassChangeError failure =
        assertThrows(
            IncompatibleClassChangeError.class,
            () -> GeneratedModelAbi.requireCompatible(GeneratedModelAbi.CURRENT + 1));

    assertTrue(failure.getMessage().contains("generated-model ABI"));
    assertTrue(failure.getMessage().contains("runtime ABI"));
  }
}
