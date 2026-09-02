package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Immutable scalar query whose selected SQL value may be null. */
public interface NullableScalarQuery<E, V> {

  NullableScalarQuery<E, V> where(QueryPredicate<E> predicate);

  NullableScalarQuery<E, V> and(QueryPredicate<E> predicate);

  NullableScalarQuery<E, V> or(QueryPredicate<E> predicate);

  NullableScalarQuery<E, V> withOptions(ExecutionOptions executionOptions);

  @SuppressWarnings("unchecked")
  NullableScalarQuery<E, V> orderBy(SortSpecification<E>... specifications);

  NullableScalarQuery<E, V> thenByPrimaryKey(SortDirection direction);

  NullableScalarQuery<E, V> distinct();

  /** Creates an independent count plan from this query's source, predicate, and distinct shape. */
  CountQuery countQuery();

  SingleRow<V> fetchOne();

  SingleRow<V> fetchFirst();

  List<@Nullable V> fetchList();

  default List<@Nullable V> fetch() {
    return fetchList();
  }

  Page<@Nullable V> fetchPage(PageRequest request);

  /** Executes a page with a caller-supplied equivalent count plan. */
  Page<@Nullable V> fetchPage(PageRequest request, CountQuery explicitCountQuery);

  Slice<@Nullable V> fetchSlice(SliceRequest request);

  QueryCursor<@Nullable V> cursor();

  CloseableQueryStream<@Nullable V> stream();
}
