package io.skis.query;

import io.skis.core.ExecutionOptions;
import io.skis.jdbc.JdbcRow;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Nullable scalar facade over the shared unified query structure. */
final class DefaultNullableScalarQuery<E, V> implements NullableScalarQuery<E, V> {

  private final DefaultQueryOperations operations;
  private final DefaultSelectQuery<E, V> delegate;

  DefaultNullableScalarQuery(DefaultQueryOperations operations, DefaultSelectQuery<E, V> delegate) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public NullableScalarQuery<E, V> where(QueryPredicate<E> predicate) {
    return wrap(delegate.where(predicate));
  }

  @Override
  public NullableScalarQuery<E, V> and(QueryPredicate<E> predicate) {
    return wrap(delegate.and(predicate));
  }

  @Override
  public NullableScalarQuery<E, V> or(QueryPredicate<E> predicate) {
    return wrap(delegate.or(predicate));
  }

  @Override
  public NullableScalarQuery<E, V> withOptions(ExecutionOptions executionOptions) {
    return wrap(delegate.withOptions(executionOptions));
  }

  @SafeVarargs
  @Override
  public final NullableScalarQuery<E, V> orderBy(SortSpecification<E>... specifications) {
    return wrap(delegate.orderBy(specifications));
  }

  @Override
  public NullableScalarQuery<E, V> thenByPrimaryKey(SortDirection direction) {
    return wrap(delegate.thenByPrimaryKey(direction));
  }

  @Override
  public NullableScalarQuery<E, V> distinct() {
    return wrap(delegate.distinct());
  }

  @Override
  public CountQuery countQuery() {
    return delegate.countQuery();
  }

  @Override
  public SingleRow<V> fetchOne() {
    QueryCompilation<V> query = delegate.compilation(QueryPagination.None.INSTANCE);
    return singleRow(
        operations.fetchNullableOne(query.plan(), query.argument(), delegate.executionContext()));
  }

  @Override
  public SingleRow<V> fetchFirst() {
    QueryCompilation<V> query = delegate.compilation(new QueryPagination.LimitOnly(1));
    return singleRow(
        operations.fetchNullableFirst(query.plan(), query.argument(), delegate.executionContext()));
  }

  @Override
  public List<@Nullable V> fetchList() {
    return delegate.fetchNullableList();
  }

  @Override
  public Page<@Nullable V> fetchPage(PageRequest request) {
    return delegate.fetchNullablePage(request);
  }

  @Override
  public Page<@Nullable V> fetchPage(PageRequest request, CountQuery explicitCountQuery) {
    return delegate.fetchNullablePage(request, explicitCountQuery);
  }

  @Override
  public Slice<@Nullable V> fetchSlice(SliceRequest request) {
    return delegate.fetchNullableSlice(request);
  }

  @Override
  public QueryCursor<@Nullable V> cursor() {
    return delegate.nullableCursor();
  }

  @Override
  public CloseableQueryStream<@Nullable V> stream() {
    return delegate.nullableStream();
  }

  private DefaultNullableScalarQuery<E, V> wrap(DefaultSelectQuery<E, V> query) {
    return query == delegate ? this : new DefaultNullableScalarQuery<>(operations, query);
  }

  private static <V> SingleRow<V> singleRow(JdbcRow<V> row) {
    return row.present() ? SingleRow.present(row.value()) : SingleRow.noRow();
  }
}
