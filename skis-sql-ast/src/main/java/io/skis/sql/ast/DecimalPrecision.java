package io.skis.sql.ast;

/** Controlled precision/scale for a finite exact-numeric CAST target. */
public record DecimalPrecision(int precision, int scale) {

  public DecimalPrecision {
    if (precision < 1 || precision > 1000 || scale < 0 || scale > precision) {
      throw new IllegalArgumentException(
          "portable decimal CAST requires 1 <= precision <= 1000 and 0 <= scale <= precision");
    }
  }
}
