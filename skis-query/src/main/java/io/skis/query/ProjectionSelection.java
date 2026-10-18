package io.skis.query;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.spi.ProjectionMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable binding of a generated projection mapping to one query's ordered selections. */
public final class ProjectionSelection<R> {

  private final ProjectionMapping<R> mapping;
  private final List<Selectable<?>> selections;

  ProjectionSelection(ProjectionMapping<R> mapping, List<? extends Selectable<?>> selections) {
    this.mapping = Objects.requireNonNull(mapping, "mapping");
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
    List<Selectable<?>> copy = new ArrayList<>(selections.size());
    for (Selectable<?> selection : selections) {
      copy.add(Objects.requireNonNull(selection, "selection"));
    }
    this.selections = List.copyOf(copy);
  }

  /** Infrastructure factory used by the generated projection SPI contract. */
  public static <R> ProjectionSelection<R> generated(
      ProjectionMapping<R> mapping, List<? extends Selectable<?>> selections) {
    return new ProjectionSelection<>(mapping, selections);
  }

  /** Returns the user result type produced by this selection. */
  public Class<R> resultType() {
    return mapping.resultType();
  }

  /** Returns the stable mapping identity emitted by the annotation processor. */
  public String mappingId() {
    return mapping.mappingId();
  }

  /** Returns the bound expressions in constructor-parameter order. */
  public List<Selectable<?>> selections() {
    return selections;
  }

  ProjectionMapping<R> mapping() {
    return mapping;
  }
}
