package io.skis.sql.ast;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One non-correlated VALUES row retaining logical parameters behind typed column references. */
public record ParameterRelationSource(
    List<ParameterSlot<?>> parameters, DerivedRelationReference reference)
    implements RelationSource {

  public ParameterRelationSource {
    parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    Objects.requireNonNull(reference, "reference");
    if (parameters.isEmpty() || parameters.size() != reference.outputs().size()) {
      throw new IllegalArgumentException(
          "parameter relation requires one output for each parameter");
    }
    Set<Integer> ordinals = new HashSet<>();
    for (int index = 0; index < parameters.size(); index++) {
      ParameterSlot<?> parameter = parameters.get(index);
      DerivedOutputColumn output = reference.output(index);
      if (!ordinals.add(parameter.ordinal())) {
        throw new IllegalArgumentException(
            "parameter relation must bind each logical parameter once");
      }
      if (!parameter.javaType().equals(output.javaType())
          || parameter.sqlType() != output.sqlType()
          || parameter.nullability() != output.nullability()
          || parameter.sqlType() == SqlType.OTHER) {
        throw new IllegalArgumentException(
            "parameter relation output must retain its parameter descriptor");
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
