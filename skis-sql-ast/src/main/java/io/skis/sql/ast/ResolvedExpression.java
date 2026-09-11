package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Immutable dependencies, structural key, and effective nullability of one clause expression. */
public record ResolvedExpression(
    ExpressionPosition position,
    ResolvedStructureKey structureKey,
    List<ResolvedColumnIdentity> columnDependencies,
    List<ResolvedParameterIdentity> parameterDependencies,
    Nullability effectiveNullability) {

  public ResolvedExpression {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(structureKey, "structureKey");
    Objects.requireNonNull(columnDependencies, "columnDependencies");
    columnDependencies = List.copyOf(columnDependencies);
    columnDependencies.forEach(
        dependency -> Objects.requireNonNull(dependency, "column dependency"));
    Objects.requireNonNull(parameterDependencies, "parameterDependencies");
    parameterDependencies = List.copyOf(parameterDependencies);
    parameterDependencies.forEach(
        dependency -> Objects.requireNonNull(dependency, "parameter dependency"));
    Objects.requireNonNull(effectiveNullability, "effectiveNullability");
  }
}
