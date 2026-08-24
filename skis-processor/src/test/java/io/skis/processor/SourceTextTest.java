package io.skis.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.skis.metadata.GeneratedModelAbi;
import org.junit.jupiter.api.Test;

class SourceTextTest {

  @Test
  void keepsTheGeneratorAndRuntimeAbiInSync() {
    assertEquals(GeneratedModelAbi.CURRENT, SourceText.GENERATED_ABI);
  }

  @Test
  void escapesJavaStringSyntaxAndNamedControlCharacters() {
    assertEquals("\"\\\\\\\"\\b\\t\\n\\f\\r\"", SourceText.string("\\\"\b\t\n\f\r"));
  }

  @Test
  void escapesOtherControlCharactersAsOctalAndSurrogatesAsUnicode() {
    String value =
        new String(
            new char[] {0, 1, 31, 127, 159, '\u2028', '\u2029', Character.MIN_HIGH_SURROGATE});

    assertEquals("\"\\000\\001\\037\\177\\237\\u2028\\u2029\\ud800\"", SourceText.string(value));
  }
}
