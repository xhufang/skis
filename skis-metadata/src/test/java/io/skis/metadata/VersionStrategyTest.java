package io.skis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class VersionStrategyTest {

  @Test
  void initializesMissingNumericVersionsWithTypeCorrectZero() {
    VersionStrategy strategy = VersionStrategy.NUMERIC_INCREMENT;

    assertEquals(Byte.valueOf((byte) 0), strategy.initialize(Byte.class, null));
    assertEquals(Short.valueOf((short) 0), strategy.initialize(Short.class, null));
    assertEquals(Integer.valueOf(0), strategy.initialize(Integer.class, null));
    assertEquals(Long.valueOf(0L), strategy.initialize(Long.class, null));
    assertEquals(BigInteger.ZERO, strategy.initialize(BigInteger.class, null));
    assertEquals(BigDecimal.ZERO, strategy.initialize(BigDecimal.class, null));
    assertEquals(Long.valueOf(9L), strategy.initialize(Long.class, 9L));
  }

  @Test
  void advancesNumericVersionsAndDetectsFixedWidthOverflow() {
    VersionStrategy strategy = VersionStrategy.NUMERIC_INCREMENT;

    assertEquals(Long.valueOf(4L), strategy.advance(Long.class, 3L));
    assertEquals(BigInteger.TWO, strategy.advance(BigInteger.class, BigInteger.ONE));
    assertEquals(new BigDecimal("2.5"), strategy.advance(BigDecimal.class, new BigDecimal("1.5")));
    assertThrows(ArithmeticException.class, () -> strategy.advance(Byte.class, Byte.MAX_VALUE));
    assertThrows(ArithmeticException.class, () -> strategy.advance(Long.class, Long.MAX_VALUE));
  }
}
