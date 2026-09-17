package io.skis.sql.ast;

import java.util.Objects;
import java.util.Optional;

/** A SELECT used as an explicitly aliased relation source. */
public record DerivedRelationSource(SelectStatement statement, DerivedRelationReference reference)
    implements RelationSource {

  public DerivedRelationSource {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(reference, "reference");
    if (!statement.hiddenSelections().isEmpty()) {
      throw new IllegalArgumentException("derived relation must not expose hidden SELECT items");
    }
    if (statement.selections().size() != reference.outputs().size()) {
      throw new IllegalArgumentException(
          "derived relation output shape has "
              + reference.outputs().size()
              + " columns but its SELECT has "
              + statement.selections().size());
    }
    for (int index = 0; index < reference.outputs().size(); index++) {
      DerivedOutputColumn output = reference.outputs().get(index);
      SqlExpression<?> selection = statement.selections().get(index);
      if (!output.javaType().equals(selection.javaType())) {
        throw new IllegalArgumentException(
            "derived output #"
                + index
                + " Java type "
                + output.javaType().getTypeName()
                + " differs from SELECT type "
                + selection.javaType().getTypeName());
      }
      if (output.sqlType() != selection.sqlType()) {
        throw new IllegalArgumentException(
            "derived output #"
                + index
                + " SQL type "
                + output.sqlType()
                + " differs from SELECT type "
                + selection.sqlType());
      }
      if (!output.nullability().isNullable() && selection.nullability().isNullable()) {
        throw new IllegalArgumentException(
            "derived output '"
                + output.name().value()
                + "' declares NON_NULL for a nullable SELECT expression");
      }
    }
  }

  @Override
  public String effectiveQualifier() {
    return reference.alias().value();
  }

  @Override
  public Optional<DerivedRelationReference> derivedReference() {
    return Optional.of(reference);
  }
}
