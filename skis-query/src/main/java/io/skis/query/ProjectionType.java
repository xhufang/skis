package io.skis.query;

import io.skis.query.spi.ProjectionMapping;
import io.skis.query.spi.ProjectionSource;
import java.util.List;

/** Query-independent construction and property-source contract generated for a special result. */
public interface ProjectionType<R> {

  /** Returns the immutable generated constructor mapping. */
  ProjectionMapping<R> mapping();

  /** Returns immutable property sources in constructor-parameter order. */
  List<ProjectionSource> sources();
}
