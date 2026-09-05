package io.skis.query;

import io.skis.core.ExecutionOptions;
import io.skis.jdbc.JdbcRow;
import io.skis.sql.ast.JoinType;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Nullable-result facade over the shared immutable query implementation. */
final class DefaultNullableSelectQuery<F, R> implements NullableSelectQuery<F, R> {

  private final DefaultQueryOperations operations;
  private final DefaultSelectQuery<F, R> delegate;

  DefaultNullableSelectQuery(DefaultQueryOperations operations, DefaultSelectQuery<F, R> delegate) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public NullableSelectQuery<F, R> where(QueryPredicate<F> predicate) {
    return wrap(delegate.where(predicate));
  }

  @Override
  public NullableSelectQuery<F, R> where(QueryCondition condition) {
    return wrap(delegate.where(condition));
  }

  @Override
  public NullableSelectQuery<F, R> and(QueryPredicate<F> predicate) {
    return wrap(delegate.and(predicate));
  }

  @Override
  public NullableSelectQuery<F, R> and(QueryCondition condition) {
    return wrap(delegate.and(condition));
  }

  @Override
  public NullableSelectQuery<F, R> or(QueryPredicate<F> predicate) {
    return wrap(delegate.or(predicate));
  }

  @Override
  public NullableSelectQuery<F, R> or(QueryCondition condition) {
    return wrap(delegate.or(condition));
  }

  @Override
  public <J> NullableJoinOnStep<F, R, J> join(QueryTable<J> table) {
    return innerJoin(table);
  }

  @Override
  public <J> NullableJoinOnStep<F, R, J> innerJoin(QueryTable<J> table) {
    return joinOn(JoinType.INNER, table);
  }

  @Override
  public <J> NullableJoinOnStep<F, R, J> leftJoin(QueryTable<J> table) {
    return joinOn(JoinType.LEFT, table);
  }

  @Override
  public <J> NullableJoinOnStep<F, R, J> rightJoin(QueryTable<J> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  @Override
  public <J> NullableJoinOnStep<F, R, J> fullJoin(QueryTable<J> table) {
    return joinOn(JoinType.FULL, table);
  }

  @Override
  public <J> NullableSelectQuery<F, R> crossJoin(QueryTable<J> table) {
    return appendJoin(JoinType.CROSS, Objects.requireNonNull(table, "table"), null);
  }

  @Override
  public NullableSelectQuery<F, R> withOptions(ExecutionOptions executionOptions) {
    return wrap(delegate.withOptions(executionOptions));
  }

  @Override
  public NullableSelectQuery<F, R> orderBy(SortSpecification<?>... specifications) {
    return wrap(delegate.orderBy(specifications));
  }

  @Override
  public NullableSelectQuery<F, R> thenByPrimaryKey(SortDirection direction) {
    return wrap(delegate.thenByPrimaryKey(direction));
  }

  @Override
  public NullableSelectQuery<F, R> distinct() {
    return wrap(delegate.distinct());
  }

  @Override
  public CountQuery countQuery() {
    return delegate.countQuery();
  }

  @Override
  public SingleRow<R> fetchOne() {
    QueryCompilation<R> query = delegate.compilation(QueryPagination.None.INSTANCE);
    return singleRow(
        operations.fetchNullableOne(query.plan(), query.argument(), delegate.executionContext()));
  }

  @Override
  public SingleRow<R> fetchFirst() {
    QueryCompilation<R> query = delegate.compilation(new QueryPagination.LimitOnly(1));
    return singleRow(
        operations.fetchNullableFirst(query.plan(), query.argument(), delegate.executionContext()));
  }

  @Override
  public List<@Nullable R> fetchList() {
    return delegate.fetchNullableList();
  }

  @Override
  public Page<@Nullable R> fetchPage(PageRequest request) {
    return delegate.fetchNullablePage(request);
  }

  @Override
  public Page<@Nullable R> fetchPage(PageRequest request, CountQuery explicitCountQuery) {
    return delegate.fetchNullablePage(request, explicitCountQuery);
  }

  @Override
  public Slice<@Nullable R> fetchSlice(SliceRequest request) {
    return delegate.fetchNullableSlice(request);
  }

  @Override
  public QueryCursor<@Nullable R> cursor() {
    return delegate.nullableCursor();
  }

  @Override
  public CloseableQueryStream<@Nullable R> stream() {
    return delegate.nullableStream();
  }

  DefaultNullableSelectQuery<F, R> appendJoin(
      JoinType type, QueryTable<?> table, @Nullable QueryCondition on) {
    return wrap(delegate.appendJoin(type, table, on));
  }

  private <J> NullableJoinOnStep<F, R, J> joinOn(JoinType type, QueryTable<J> table) {
    return new DefaultNullableJoinOnStep<>(this, type, Objects.requireNonNull(table, "table"));
  }

  private DefaultNullableSelectQuery<F, R> wrap(DefaultSelectQuery<F, R> query) {
    return query == delegate ? this : new DefaultNullableSelectQuery<>(operations, query);
  }

  private static <R> SingleRow<R> singleRow(JdbcRow<R> row) {
    return row.present() ? SingleRow.present(row.value()) : SingleRow.noRow();
  }
}
