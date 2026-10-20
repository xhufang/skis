package io.skis.dialect.spi;

import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ResolvedColumnReference;
import io.skis.sql.ast.ResolvedParameterIdentity;
import io.skis.sql.ast.SqlType;
import java.util.List;
import java.util.Objects;

/**
 * Restricted read-only information about one expression occurrence in a resolved statement.
 *
 * <p>The values are copied from the owning analysis. The type is only a handle to the stage that
 * produced it; the constructor is not public and occurrences cannot be manufactured outside the
 * stage implementation.
 */
public final class ExpressionInfo {

  private final Class<?> javaType;
  private final SqlType sqlType;
  private final Nullability effectiveNullability;
  private final List<ResolvedColumnReference> columnDependencies;
  private final List<ResolvedParameterIdentity> parameterDependencies;
  private final boolean correlated;

  ExpressionInfo(
      Class<?> javaType,
      SqlType sqlType,
      Nullability effectiveNullability,
      List<ResolvedColumnReference> columnDependencies,
      List<ResolvedParameterIdentity> parameterDependencies,
      boolean correlated) {
    this.javaType = Objects.requireNonNull(javaType, "javaType");
    this.sqlType = Objects.requireNonNull(sqlType, "sqlType");
    this.effectiveNullability =
        Objects.requireNonNull(effectiveNullability, "effectiveNullability");
    this.columnDependencies =
        List.copyOf(Objects.requireNonNull(columnDependencies, "columnDependencies"));
    this.parameterDependencies =
        List.copyOf(Objects.requireNonNull(parameterDependencies, "parameterDependencies"));
    this.correlated = correlated;
  }

  /** Java representation of the expression value. */
  public Class<?> javaType() {
    return javaType;
  }

  /** Portable SQL type declared by the expression. */
  public SqlType sqlType() {
    return sqlType;
  }

  /** Nullability after the complete join chain and current query-block scope are applied. */
  public Nullability effectiveNullability() {
    return effectiveNullability;
  }

  /** Physical and derived columns this occurrence depends on. */
  public List<ResolvedColumnReference> columnDependencies() {
    return columnDependencies;
  }

  /** Parameter identities observed in this occurrence. */
  public List<ResolvedParameterIdentity> parameterDependencies() {
    return parameterDependencies;
  }

  /** Whether this occurrence depends on a source in an ancestor query block. */
  public boolean correlated() {
    return correlated;
  }

  /** Whether the effective SQL value may be {@code NULL}. */
  public boolean nullable() {
    return effectiveNullability.isNullable();
  }

  @Override
  public String toString() {
    return "ExpressionInfo[javaType="
        + javaType.getTypeName()
        + ", sqlType="
        + sqlType
        + ", nullability="
        + effectiveNullability
        + ", correlated="
        + correlated
        + ']';
  }
}
