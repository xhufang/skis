package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.DerivedOutput;
import io.skis.query.DerivedRelation;
import io.skis.query.ProjectionSelection;
import io.skis.query.SelectDescription;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnSelect;
import io.skis.query.SliceContinuation;
import io.skis.query.SliceRequest;
import io.skis.query.SortSpecification;
import org.jspecify.annotations.Nullable;

/** Typed checks at DSL capture boundaries, without reflection or runtime registration. */
final class QueryModels {

  private QueryModels() {}

  private static QueryValidationException foreign() {
    return QueryValidationException.of(
        SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE, "DSL object was not created by SKIS");
  }

  @SuppressWarnings("unchecked")
  static <R extends @Nullable Object> DefaultSelectDescription<R> description(
      SelectDescription<R> value) {
    if (value instanceof DefaultSelectDescription<?> result) {
      return (DefaultSelectDescription<R>) result;
    }
    throw foreign();
  }

  @SuppressWarnings("unchecked")
  static <R extends @Nullable Object> DefaultSingleColumnSelect<R> single(
      SingleColumnSelect<R> value) {
    if (value instanceof DefaultSingleColumnSelect<?> result) {
      return (DefaultSingleColumnSelect<R>) result;
    }
    throw foreign();
  }

  static DefaultDerivedRelation relation(DerivedRelation value) {
    if (value instanceof DefaultDerivedRelation result) {
      return result;
    }
    throw foreign();
  }

  @SuppressWarnings("unchecked")
  static <V> DefaultDerivedOutput<V> output(DerivedOutput<V> value) {
    if (value instanceof DefaultDerivedOutput<?> result) {
      return (DefaultDerivedOutput<V>) result;
    }
    throw foreign();
  }

  @SuppressWarnings("unchecked")
  static <R> DefaultProjectionSelection<R> projection(ProjectionSelection<R> value) {
    if (value instanceof DefaultProjectionSelection<?> result) {
      return (DefaultProjectionSelection<R>) result;
    }
    throw foreign();
  }

  static DefaultSortSpecification sort(SortSpecification value) {
    if (value instanceof DefaultSortSpecification result) {
      return result;
    }
    throw foreign();
  }

  static DefaultSliceContinuation continuation(SliceContinuation value) {
    if (value instanceof DefaultSliceContinuation result) {
      return result;
    }
    throw foreign();
  }

  static DefaultSliceRequest sliceRequest(SliceRequest value) {
    if (value instanceof DefaultSliceRequest result) {
      return result;
    }
    throw foreign();
  }

  static Selectable<?> selectable(SortSpecification value) {
    return sort(value).selectable();
  }

  static <V> Selectable<V> selectable(DerivedOutput<V> value) {
    return output(value).selectable();
  }
}
