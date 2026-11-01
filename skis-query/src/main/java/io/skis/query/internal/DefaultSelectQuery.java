package io.skis.query.internal;

import io.skis.core.ExecutionOptions;
import io.skis.query.CountQuery;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryCursor;
import io.skis.query.QueryRequest;
import io.skis.query.SelectQuery;
import io.skis.query.SingleRow;
import io.skis.query.Slice;
import io.skis.query.SliceRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Independent SELECT model; execution requests share its structure and captured snapshots. */
class DefaultSelectQuery<R extends @Nullable Object> extends AbstractSelectQuery<R, SelectQuery<R>>
    implements SelectQuery<R> {

  DefaultSelectQuery(
      SelectQueryState<R> state, QueryParameters parameters, Set<QueryParameter<?>> ordering) {
    super(state, parameters, ordering);
  }

  @Override
  DefaultSelectQuery<R> recreate(
      SelectQueryState<R> state, QueryParameters values, Set<QueryParameter<?>> ordering) {
    return new DefaultSelectQuery<>(state, values, ordering);
  }

  @Override
  public CountQuery countQuery() {
    return new DefaultCountQuery(this);
  }

  @Override
  public QueryRequest<SingleRow<R>> fetchOne() {
    return request(DefaultExecutableSelectQuery::fetchOne);
  }

  @Override
  public QueryRequest<SingleRow<R>> fetchFirst() {
    return request(DefaultExecutableSelectQuery::fetchFirst);
  }

  @Override
  public QueryRequest<List<R>> fetchList() {
    return request(DefaultExecutableSelectQuery::fetchList);
  }

  @Override
  public QueryRequest<Page<R>> fetchPage(PageRequest page) {
    Objects.requireNonNull(page, "request");
    return request(query -> query.fetchPage(page));
  }

  @Override
  public QueryRequest<Page<R>> fetchPage(PageRequest page, CountQuery count) {
    Objects.requireNonNull(page, "request");
    DefaultExecutableSelectQuery.requireBuiltInCount(count);
    return request(query -> query.fetchPage(page, count));
  }

  @Override
  public QueryRequest<Slice<R>> fetchSlice(SliceRequest slice) {
    QueryModels.sliceRequest(Objects.requireNonNull(slice, "request"));
    return request(query -> query.fetchSlice(slice));
  }

  @Override
  public QueryRequest<QueryCursor<R>> cursor() {
    return request(DefaultExecutableSelectQuery::cursor);
  }

  @Override
  public QueryRequest<Stream<R>> stream() {
    return request(DefaultExecutableSelectQuery::stream);
  }

  private <T> QueryRequest<T> request(Function<DefaultExecutableSelectQuery<R>, T> terminal) {
    return new DefaultQueryRequest<>(this, terminal, ExecutionOptions.NONE);
  }
}
