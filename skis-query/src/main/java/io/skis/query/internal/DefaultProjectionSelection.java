package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.ProjectionSelection;
import io.skis.query.Selectable;
import io.skis.query.spi.ProjectionMapping;
import java.util.List;
import java.util.Objects;

/** Immutable binding of a generated projection mapping to one query's ordered selections. */
record DefaultProjectionSelection<R>(ProjectionMapping<R> mapping, List<Selectable<?>> selections)
    implements ProjectionSelection<R> {

  DefaultProjectionSelection {
    Objects.requireNonNull(mapping, "mapping");
    Objects.requireNonNull(selections, "selections");
    if (selections.size() != mapping.parameters().size()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PROJECTION_MAPPING,
          "projection '"
              + mapping.resultType().getTypeName()
              + "' requires "
              + mapping.parameters().size()
              + " selections but received "
              + selections.size());
    }
    for (Selectable<?> selection : selections) {
      SelectableSupport.requireFramework(selection);
    }
    selections = List.copyOf(selections);
  }

  /** Returns the user result type produced by this selection. */
  @Override
  public Class<R> resultType() {
    return mapping.resultType();
  }

  /** Returns the stable mapping identity emitted by the annotation processor. */
  @Override
  public String mappingId() {
    return mapping.mappingId();
  }
}
