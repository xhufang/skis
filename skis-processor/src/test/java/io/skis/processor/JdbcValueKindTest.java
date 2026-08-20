package io.skis.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.Test;

class JdbcValueKindTest {

  @Test
  void resolvesPrimitiveAndDeclaredTypesFromOneDescriptorTable() {
    assertSame(JdbcValueKind.BOOLEAN, JdbcValueKind.forPrimitive(TypeKind.BOOLEAN));
    assertSame(JdbcValueKind.INTEGER, JdbcValueKind.forPrimitive(TypeKind.INT));
    assertSame(JdbcValueKind.CHARACTER, JdbcValueKind.forPrimitive(TypeKind.CHAR));
    assertSame(JdbcValueKind.UUID, JdbcValueKind.forDeclared("java.util.UUID"));
    assertSame(
        JdbcValueKind.LOCAL_DATE_TIME,
        JdbcValueKind.forDeclared("java.time.LocalDateTime"));
    assertSame(JdbcValueKind.UNSUPPORTED, JdbcValueKind.forDeclared("samples.Money"));
  }

  @Test
  void suppliesPrimitiveAndReferenceEntryPointsToBothGenerators() {
    assertEquals("readLong", JdbcValueKind.LONG.readMethod(true));
    assertEquals("readNullableLong", JdbcValueKind.LONG.readMethod(false));
    assertEquals("bindLong", JdbcValueKind.LONG.bindMethod(true));
    assertEquals("bindNullableLong", JdbcValueKind.LONG.bindMethod(false));
    assertEquals("readString", JdbcValueKind.STRING.readMethod(false));
    assertEquals("bindString", JdbcValueKind.STRING.bindMethod(false));
    assertThrows(IllegalStateException.class, () -> JdbcValueKind.STRING.readMethod(true));
  }

  @Test
  void limitsNumericVersionsToExactCounterTypes() {
    assertTrue(JdbcValueKind.LONG.numeric());
    assertTrue(JdbcValueKind.BIG_INTEGER.numeric());
    assertTrue(JdbcValueKind.BIG_DECIMAL.numeric());
    assertFalse(JdbcValueKind.FLOAT.numeric());
    assertFalse(JdbcValueKind.DOUBLE.numeric());
  }
}
