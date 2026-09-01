package io.skis.sql.ast;

import java.util.Objects;

/**
 * An allow-listed SQL literal that cannot carry application text or arbitrary runtime values.
 *
 * <p>All ordinary values belong in {@link ParameterSlot}; this node is limited to standard
 * framework constants needed to express SQL structure.
 */
public final class LiteralExpression<T> implements SqlExpression<T> {

  /** SQL tokens that the portable renderer may emit directly. */
  public enum Kind {
    NULL,
    TRUE,
    FALSE,
    ZERO,
    ONE
  }

  private static final LiteralExpression<Boolean> TRUE =
      new LiteralExpression<>(Kind.TRUE, Boolean.class, SqlType.BOOLEAN, Nullability.NON_NULL);
  private static final LiteralExpression<Boolean> FALSE =
      new LiteralExpression<>(Kind.FALSE, Boolean.class, SqlType.BOOLEAN, Nullability.NON_NULL);

  private final Kind kind;
  private final Class<T> javaType;
  private final SqlType sqlType;
  private final Nullability nullability;

  private LiteralExpression(
      Kind kind, Class<T> javaType, SqlType sqlType, Nullability nullability) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.javaType = Objects.requireNonNull(javaType, "javaType");
    this.sqlType = Objects.requireNonNull(sqlType, "sqlType");
    this.nullability = Objects.requireNonNull(nullability, "nullability");
    SemanticValidator.validateLiteral(kind, javaType, sqlType, nullability);
  }

  /** Returns the non-null standard boolean {@code TRUE} literal. */
  public static LiteralExpression<Boolean> trueLiteral() {
    return TRUE;
  }

  /** Returns the non-null standard boolean {@code FALSE} literal. */
  public static LiteralExpression<Boolean> falseLiteral() {
    return FALSE;
  }

  /** Creates a typed SQL {@code NULL}; use {@code IS NULL} rather than ordinary comparison. */
  public static <T> LiteralExpression<T> nullLiteral(Class<T> javaType) {
    return new LiteralExpression<>(
        Kind.NULL,
        javaType,
        SqlType.fromJavaType(javaType),
        Nullability.NULLABLE);
  }

  /** Creates the allow-listed numeric literal {@code 0} for a numeric Java representation. */
  public static <T> LiteralExpression<T> zero(Class<T> javaType) {
    return numeric(Kind.ZERO, javaType);
  }

  /** Creates the allow-listed numeric literal {@code 1} for a numeric Java representation. */
  public static <T> LiteralExpression<T> one(Class<T> javaType) {
    return numeric(Kind.ONE, javaType);
  }

  private static <T> LiteralExpression<T> numeric(Kind kind, Class<T> javaType) {
    Class<T> boxedType = SemanticValidator.boxedJavaType(javaType);
    return new LiteralExpression<>(
        kind,
        boxedType,
        SqlType.fromJavaType(boxedType),
        Nullability.NON_NULL);
  }

  public Kind kind() {
    return kind;
  }

  @Override
  public Class<T> javaType() {
    return javaType;
  }

  @Override
  public SqlType sqlType() {
    return sqlType;
  }

  @Override
  public Nullability nullability() {
    return nullability;
  }

  @Override
  public boolean nullable() {
    return nullability.isNullable();
  }

  /** Whether this node is the statically known SQL {@code NULL} literal. */
  public boolean isNullLiteral() {
    return kind == Kind.NULL;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof LiteralExpression<?> literal
            && kind == literal.kind
            && javaType.equals(literal.javaType)
            && sqlType == literal.sqlType
            && nullability == literal.nullability;
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, javaType, sqlType, nullability);
  }
}
