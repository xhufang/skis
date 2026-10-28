package io.skis.query;

import io.skis.query.internal.QueryDsl;
import io.skis.query.spi.ProjectionMapping;
import java.util.List;

/** Immutable binding of a generated projection mapping to one query's ordered selections. */
public interface ProjectionSelection<R> {

  /** Infrastructure factory used by the generated projection SPI contract. */
  static <R> ProjectionSelection<R> generated(
      ProjectionMapping<R> mapping, List<? extends Selectable<?>> selections) {
    return QueryDsl.projection(mapping, selections);
  }

  /** Returns the user result type produced by this selection. */
  Class<R> resultType();

  /** Returns the stable mapping identity emitted by the annotation processor. */
  String mappingId();

  /** Returns the bound expressions in constructor-parameter order. */
  List<Selectable<?>> selections();

  /** Returns the immutable generated constructor mapping bound by this selection. */
  ProjectionMapping<R> mapping();
}
