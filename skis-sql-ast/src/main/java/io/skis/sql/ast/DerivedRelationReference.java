package io.skis.sql.ast;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-owned reference and ordered output shape for one derived-relation occurrence.
 *
 * <p>Scope resolution matches this object by reference identity. Equality remains structural so AST
 * equality and fingerprints never depend on a JVM object address.
 */
public record DerivedRelationReference(Identifier alias, List<DerivedOutputColumn> outputs) {

  public DerivedRelationReference(Identifier alias, List<DerivedOutputColumn> outputs) {
    this.alias = Objects.requireNonNull(alias, "alias");
    this.outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    if (this.outputs.isEmpty()) {
      throw new IllegalArgumentException("derived relation requires at least one output column");
    }
    Set<Identifier> names = new HashSet<>();
    for (DerivedOutputColumn output : this.outputs) {
      Objects.requireNonNull(output, "derived output");
      if (!names.add(output.name())) {
        throw new IllegalArgumentException(
            "derived output alias '" + output.name().value() + "' is duplicated");
      }
    }
  }

  public DerivedOutputColumn output(int ordinal) {
    if (ordinal < 0 || ordinal >= outputs.size()) {
      throw new IllegalArgumentException(
          "derived output ordinal "
              + ordinal
              + " is outside shape of "
              + outputs.size()
              + " columns");
    }
    return outputs.get(ordinal);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other
                instanceof
                DerivedRelationReference(Identifier alias1, List<DerivedOutputColumn> outputs1)
            && alias.equals(alias1)
            && outputs.equals(outputs1);
  }

  @Override
  public String toString() {
    return alias.value();
  }
}
