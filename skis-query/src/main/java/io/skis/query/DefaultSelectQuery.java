package io.skis.query;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.jdbc.JdbcPageResult;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Built-in unified immutable query implementation. */
final class DefaultSelectQuery<E, R> implements SelectQuery<E, R> {

  private final DefaultQueryOperations operations;
  private final EntityPlanSet<E> plans;
  private final QueryTable<E> table;
  private final SelectedResult<?, R> selected;
  private final List<QueryJoin> joins;
  private final @Nullable QueryCondition predicate;
  private final ExecutionContext executionContext;
  private final List<SortSpecification<E>> orderBy;
  private final boolean distinct;
  private final AtomicReference<@Nullable CompiledQueryPlan<R, Object>> fastPlan =
      new AtomicReference<>();
  private final LocalPlanCache<R> plansByPagination = new LocalPlanCache<>();
  private final LocalPlanCache<OrderedRow<R>> orderedPlansByPagination = new LocalPlanCache<>();
  private final AtomicReference<@Nullable CachedPlan<Long>> countPlan = new AtomicReference<>();

  static <E, R> DefaultSelectQuery<E, R> create(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      SelectedResult<?, R> selected) {
    return new DefaultSelectQuery<>(
        operations,
        plans,
        table,
        selected,
        List.of(),
        null,
        ExecutionContext.EMPTY,
        List.of(),
        false);
  }

  private DefaultSelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      SelectedResult<?, R> selected,
      List<QueryJoin> joins,
      @Nullable QueryCondition predicate,
      ExecutionContext executionContext,
      List<SortSpecification<E>> orderBy,
      boolean distinct) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.table = Objects.requireNonNull(table, "table");
    this.selected = Objects.requireNonNull(selected, "selected");
    this.joins = List.copyOf(joins);
    this.predicate = predicate;
    this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
    this.orderBy = List.copyOf(orderBy);
    this.distinct = distinct;
  }

  @Override
  public DefaultSelectQuery<E, R> where(QueryPredicate<E> newPredicate) {
    return where((QueryCondition) newPredicate);
  }

  @Override
  public DefaultSelectQuery<E, R> where(QueryCondition newPredicate) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate != null) {
      throw new QueryValidationException("where(...) may only be called once per query");
    }
    return copy(newPredicate, executionContext, orderBy, distinct);
  }

  @Override
  public DefaultSelectQuery<E, R> and(QueryPredicate<E> newPredicate) {
    return chainNarrow(newPredicate, true);
  }

  @Override
  public DefaultSelectQuery<E, R> and(QueryCondition newPredicate) {
    return chain(newPredicate, true);
  }

  @Override
  public DefaultSelectQuery<E, R> or(QueryPredicate<E> newPredicate) {
    return chainNarrow(newPredicate, false);
  }

  @Override
  public DefaultSelectQuery<E, R> or(QueryCondition newPredicate) {
    return chain(newPredicate, false);
  }

  @Override
  public <J> JoinOnStep<E, R, J> join(QueryTable<J> joinedTable) {
    return innerJoin(joinedTable);
  }

  @Override
  public <J> JoinOnStep<E, R, J> innerJoin(QueryTable<J> joinedTable) {
    return joinOn(JoinType.INNER, joinedTable);
  }

  @Override
  public <J> JoinOnStep<E, R, J> leftJoin(QueryTable<J> joinedTable) {
    return joinOn(JoinType.LEFT, joinedTable);
  }

  @Override
  public <J> JoinOnStep<E, R, J> rightJoin(QueryTable<J> joinedTable) {
    return joinOn(JoinType.RIGHT, joinedTable);
  }

  @Override
  public <J> JoinOnStep<E, R, J> fullJoin(QueryTable<J> joinedTable) {
    return joinOn(JoinType.FULL, joinedTable);
  }

  @Override
  public <J> DefaultSelectQuery<E, R> crossJoin(QueryTable<J> joinedTable) {
    return appendJoin(JoinType.CROSS, Objects.requireNonNull(joinedTable, "table"), null);
  }

  @Override
  public DefaultSelectQuery<E, R> withOptions(ExecutionOptions executionOptions) {
    ExecutionContext context =
        ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions"));
    return executionContext.executionOptions().equals(context.executionOptions())
        ? this
        : copy(predicate, context, orderBy, distinct);
  }

  @SafeVarargs
  @Override
  public final DefaultSelectQuery<E, R> orderBy(SortSpecification<E>... specifications) {
    Objects.requireNonNull(specifications, "specifications");
    if (specifications.length == 0) {
      throw new QueryValidationException("orderBy requires at least one ordering item");
    }
    List<SortSpecification<E>> items = List.copyOf(Arrays.asList(specifications.clone()));
    validateOrderOwnership(items);
    return orderBy.equals(items) ? this : copy(predicate, executionContext, items, distinct);
  }

  @Override
  public DefaultSelectQuery<E, R> thenByPrimaryKey(SortDirection direction) {
    Objects.requireNonNull(direction, "direction");
    PrimaryKeyMeta<E> primaryKey =
        plans
            .entity()
            .primaryKey()
            .orElseThrow(
                () ->
                    new QueryValidationException(
                        "thenByPrimaryKey requires primary-key metadata for entity '"
                            + plans.entity().entityName()
                            + "'"));
    List<SortSpecification<E>> items = new ArrayList<>(orderBy);
    for (PropertyMeta<E, ?> property : primaryKey.properties()) {
      boolean present = items.stream().anyMatch(item -> item.column().property() == property);
      if (!present) {
        items.add(
            new SortSpecification<>(
                table.queryColumn(property), direction, NullPlacement.DIALECT_DEFAULT));
      }
    }
    return items.equals(orderBy) ? this : copy(predicate, executionContext, items, distinct);
  }

  @Override
  public DefaultSelectQuery<E, R> distinct() {
    return distinct ? this : copy(predicate, executionContext, orderBy, true);
  }

  @Override
  public CountQuery countQuery() {
    return new DefaultCountQuery(operations, this);
  }

  @Override
  public Optional<R> fetchOne() {
    if (isFastPathShape(QueryPagination.None.INSTANCE)) {
      return operations.fetchOne(fastPlan(), fastArgument(), executionContext);
    }
    QueryCompilation<R> query = compilation(QueryPagination.None.INSTANCE);
    return operations.fetchOne(query.plan(), query.argument(), executionContext);
  }

  @Override
  public Optional<R> fetchFirst() {
    QueryCompilation<R> query = compilation(new QueryPagination.LimitOnly(1));
    return operations.fetchFirst(query.plan(), query.argument(), executionContext);
  }

  @Override
  public List<R> fetchList() {
    return requireNonNullValues(fetchListResult(), "non-null query produced a null list item");
  }

  List<@Nullable R> fetchNullableList() {
    return fetchListResult();
  }

  private List<@Nullable R> fetchListResult() {
    if (isFastPathShape(QueryPagination.None.INSTANCE)) {
      return operations.fetchList(fastPlan(), fastArgument(), executionContext);
    }
    QueryCompilation<R> query = compilation(QueryPagination.None.INSTANCE);
    return operations.fetchList(query.plan(), query.argument(), executionContext);
  }

  @Override
  public Page<R> fetchPage(PageRequest request) {
    validatePageRequest(request);
    return requireNonNullPage(fetchPageResult(request, countCompilation()));
  }

  @Override
  public Page<R> fetchPage(PageRequest request, CountQuery explicitCountQuery) {
    validatePageRequest(request);
    return requireNonNullPage(
        fetchPageResult(
            request,
            requireExplicitCount(
                Objects.requireNonNull(explicitCountQuery, "explicitCountQuery"))));
  }

  Page<@Nullable R> fetchNullablePage(PageRequest request) {
    validatePageRequest(request);
    return fetchPageResult(request, countCompilation());
  }

  Page<@Nullable R> fetchNullablePage(PageRequest request, CountQuery explicitCountQuery) {
    validatePageRequest(request);
    return fetchPageResult(
        request,
        requireExplicitCount(Objects.requireNonNull(explicitCountQuery, "explicitCountQuery")));
  }

  private Page<@Nullable R> fetchPageResult(PageRequest request, QueryCompilation<Long> count) {
    QueryCompilation<R> content =
        compilation(new QueryPagination.Offset(request.pageSize(), request.offset()));
    JdbcPageResult<R> result = operations.fetchPage(content, count, executionContext);
    return Page.of(result.items(), request, result.totalElements());
  }

  private void validatePageRequest(PageRequest request) {
    Objects.requireNonNull(request, "request");
    validatePaginationOrder(false);
    operations.validateRequestedRows(request.pageSize(), executionContext);
  }

  @Override
  public Slice<R> fetchSlice(SliceRequest request) {
    return requireNonNullSlice(fetchSliceResult(request));
  }

  Slice<@Nullable R> fetchNullableSlice(SliceRequest request) {
    return fetchSliceResult(request);
  }

  private Slice<@Nullable R> fetchSliceResult(SliceRequest request) {
    Objects.requireNonNull(request, "request");
    validatePaginationOrder(false);
    operations.validateRequestedRows(request.pageSize(), executionContext);
    return switch (request.mode()) {
      case OFFSET -> fetchOffsetSlice(request.offset(), request.pageSize());
      case KEYSET_FIRST -> fetchKeysetSlice(null, request.pageSize());
      case RESUME -> resumeSlice(request.continuation(), request.pageSize());
    };
  }

  @Override
  public QueryCursor<R> cursor() {
    QueryCompilation<R> query = compilation(QueryPagination.None.INSTANCE);
    return operations.cursor(query.plan(), query.argument(), executionContext);
  }

  @Override
  public CloseableQueryStream<R> stream() {
    return new CloseableQueryStream<>(cursor());
  }

  QueryCursor<@Nullable R> nullableCursor() {
    QueryCompilation<R> query = compilation(QueryPagination.None.INSTANCE);
    return operations.nullableCursor(query.plan(), query.argument(), executionContext);
  }

  CloseableQueryStream<@Nullable R> nullableStream() {
    return new CloseableQueryStream<>(nullableCursor());
  }

  QueryCompilation<R> compilation(QueryPagination pagination) {
    validateDistinctOrdering();
    if (isFastPathShape(pagination)) {
      return unpaginatedCompilation();
    }
    return plansByPagination.getOrCompile(
        pagination,
        () ->
            plans
                .compiler()
                .compileSelection(
                    plans.model(),
                    table,
                    selection(),
                    joins,
                    predicate,
                    orderBy,
                    distinct,
                    pagination,
                    List.of()),
        paginationArgument(pagination));
  }

  ExecutionContext executionContext() {
    return executionContext;
  }

  private QueryCompilation<OrderedRow<R>> orderedCompilation(QueryPagination pagination) {
    validateDistinctOrdering();
    return orderedPlansByPagination.getOrCompile(
        pagination,
        () ->
            plans
                .compiler()
                .compileOrdered(
                    plans.model(),
                    table,
                    selection(),
                    joins,
                    predicate,
                    orderBy,
                    distinct,
                    pagination),
        paginationArgument(pagination));
  }

  private boolean isFastPathShape(QueryPagination pagination) {
    return pagination == QueryPagination.None.INSTANCE
        && joins.isEmpty()
        && selected.belongsTo(table)
        && (predicate == null || predicate instanceof QueryPredicate<?>)
        && orderBy.isEmpty()
        && !distinct;
  }

  private QueryCompilation<R> unpaginatedCompilation() {
    CompiledQueryStructure structure = QueryStructureCompiler.compile(table, joins, predicate);
    return new QueryCompilation<>(
        fastPlan(),
        fastArgument(),
        new SelectStatement(
            selection().expressions(), structure.fromClause(), structure.where()));
  }

  private CompiledQueryPlan<R, Object> fastPlan() {
    CompiledQueryPlan<R, Object> existing = fastPlan.get();
    if (existing != null) {
      return existing;
    }
    CompiledQueryPlan<R, Object> compiled = selected.fastPlan(fastPredicate());
    CompiledQueryPlan<R, Object> published = fastPlan.compareAndExchange(null, compiled);
    return published == null ? compiled : published;
  }

  QueryCompilation<Long> countCompilation() {
    CachedPlan<Long> existing = countPlan.get();
    if (existing != null) {
      return new QueryCompilation<>(existing.plan(), conditionArgument(), existing.ast());
    }
    QueryCompilation<Long> compiled =
        plans
            .compiler()
            .compileCount(plans.model(), table, selection(), joins, predicate, distinct);
    CachedPlan<Long> cached = new CachedPlan<>(compiled.plan(), compiled.ast());
    CachedPlan<Long> published = countPlan.compareAndExchange(null, cached);
    return published == null
        ? compiled
        : new QueryCompilation<>(published.plan(), conditionArgument(), published.ast());
  }

  private Object paginationArgument(QueryPagination pagination) {
    List<Object> arguments = new ArrayList<>(conditionArguments());
    switch (pagination) {
      case QueryPagination.None ignored -> {}
      case QueryPagination.LimitOnly limit -> arguments.add(limit.limit());
      case QueryPagination.Offset offset -> {
        arguments.add(offset.limit());
        arguments.add(offset.offset());
      }
      case QueryPagination.Keyset keyset -> {
        keyset.values().stream().filter(Objects::nonNull).forEach(arguments::add);
        arguments.add(keyset.limit());
      }
    }
    return arguments.isEmpty() ? NoParameters.INSTANCE : new QueryArguments(arguments);
  }

  private QueryPlanCompiler.Selection<R> selection() {
    return selected.selection();
  }

  private QueryCompilation<Long> requireExplicitCount(CountQuery explicitCountQuery) {
    if (!(explicitCountQuery instanceof DefaultCountQuery countQuery)) {
      throw new QueryValidationException(
          "explicit count must be a built-in SKIS CountQuery created with countQuery()");
    }
    if (countQuery.operations() != operations) {
      throw new QueryValidationException(
          "explicit count must use the same execution and data-source scope as the content query");
    }
    if (!countQuery
        .executionContext()
        .executionOptions()
        .equals(executionContext.executionOptions())) {
      throw new QueryValidationException(
          "explicit count must use the same execution options as the content query");
    }
    return countQuery.compilation();
  }

  private Slice<@Nullable R> resumeSlice(SliceContinuation continuation, int pageSize) {
    validateContinuation(continuation);
    return switch (continuation.mode()) {
      case OFFSET -> fetchOffsetSlice(continuation.nextOffset(), pageSize);
      case KEYSET -> fetchKeysetSlice(continuation, pageSize);
    };
  }

  private Slice<@Nullable R> fetchOffsetSlice(long offset, int pageSize) {
    int limit = sizePlusOne(pageSize);
    QueryCompilation<R> query = compilation(new QueryPagination.Offset(limit, offset));
    List<@Nullable R> rows =
        operations.fetchSliceList(query.plan(), query.argument(), executionContext, pageSize);
    boolean hasNext = rows.size() > pageSize;
    List<@Nullable R> items = visibleRows(rows, pageSize);
    SliceContinuation continuation =
        hasNext
            ? SliceContinuation.offset(
                queryFingerprint(),
                orderSignature(),
                nextOffset(offset, pageSize),
                parameterDigest())
            : null;
    return Slice.of(items, pageSize, continuation);
  }

  private Slice<@Nullable R> fetchKeysetSlice(
      @Nullable SliceContinuation continuation, int pageSize) {
    validatePaginationOrder(true);
    List<@Nullable Object> anchors = continuation == null ? null : continuation.keysetValues();
    QueryPagination pagination =
        anchors == null
            ? new QueryPagination.LimitOnly(sizePlusOne(pageSize))
            : new QueryPagination.Keyset(sizePlusOne(pageSize), anchors);
    QueryCompilation<OrderedRow<R>> query = orderedCompilation(pagination);
    List<OrderedRow<R>> rows =
        requireNonNullValues(
            operations.fetchSliceList(query.plan(), query.argument(), executionContext, pageSize),
            "ordered query produced a null row");
    boolean hasNext = rows.size() > pageSize;
    List<@Nullable R> items = new ArrayList<>(Math.min(rows.size(), pageSize));
    for (int index = 0; index < Math.min(rows.size(), pageSize); index++) {
      items.add(rows.get(index).value());
    }
    SliceContinuation next = null;
    if (hasNext) {
      OrderedRow<R> anchor = rows.get(pageSize - 1);
      List<Boolean> nullMarkers = anchor.orderValues().stream().map(Objects::isNull).toList();
      next =
          SliceContinuation.keyset(
              queryFingerprint(),
              orderSignature(),
              orderBy.stream().map(item -> item.column().sqlType()).toList(),
              nullMarkers,
              anchor.orderValues(),
              parameterDigest());
    }
    return Slice.of(items, pageSize, next);
  }

  private void validatePaginationOrder(boolean keyset) {
    if (orderBy.isEmpty()) {
      throw new QueryValidationException("pagination requires explicit stable ORDER BY");
    }
    if (!hasStableDistinctOrdering()) {
      PrimaryKeyMeta<E> primaryKey =
          plans
              .entity()
              .primaryKey()
              .orElseThrow(
                  () ->
                      new QueryValidationException(
                          "pagination requires primary-key metadata for stable ordering"));
      for (PropertyMeta<E, ?> property : primaryKey.properties()) {
        if (orderBy.stream().noneMatch(item -> item.column().property() == property)) {
          throw new QueryValidationException(
              "pagination ORDER BY must include the complete primary key; missing property '"
                  + property.name()
                  + "'");
        }
      }
    }
    if (keyset) {
      for (SortSpecification<E> item : orderBy) {
        if (item.column().nullable() && item.nullPlacement() == NullPlacement.DIALECT_DEFAULT) {
          throw new QueryValidationException(
              "nullable keyset ordering property '"
                  + item.column().property().name()
                  + "' must declare nullsFirst() or nullsLast()");
        }
      }
    }
  }

  private boolean hasStableDistinctOrdering() {
    if (!distinct) {
      return false;
    }
    List<SqlExpression<?>> selected = selection().expressions();
    return selected.stream()
        .allMatch(
            expression ->
                orderBy.stream().anyMatch(item -> item.column().expression().equals(expression)));
  }

  private void validateOrderOwnership(List<SortSpecification<E>> items) {
    Set<PropertyMeta<E, ?>> properties = new HashSet<>();
    for (SortSpecification<E> item : items) {
      Objects.requireNonNull(item, "ordering item");
      if (item.column().expression().table() != table) {
        throw new QueryValidationException(
            "ORDER BY column belongs to a different table expression");
      }
      if (!properties.add(item.column().property())) {
        throw new QueryValidationException(
            "ORDER BY repeats property '" + item.column().property().name() + "'");
      }
    }
  }

  private void validateDistinctOrdering() {
    if (!distinct || orderBy.isEmpty()) {
      return;
    }
    List<SqlExpression<?>> expressions = selection().expressions();
    for (SortSpecification<E> item : orderBy) {
      if (!expressions.contains(item.column().expression())) {
        throw new QueryValidationException(
            "distinct ORDER BY property '"
                + item.column().property().name()
                + "' is not part of the selected result");
      }
    }
  }

  private void validateContinuation(SliceContinuation continuation) {
    if (continuation.formatVersion() != SliceContinuation.FORMAT_VERSION) {
      throw new QueryValidationException("unsupported continuation format version");
    }
    if (continuation.generatedAbi() != GeneratedModelAbi.CURRENT) {
      throw new QueryValidationException("continuation generated-model ABI is incompatible");
    }
    if (!continuation.queryFingerprint().equals(queryFingerprint())
        || !continuation.orderSignature().equals(orderSignature())
        || !continuation.parameterDigest().equals(parameterDigest())) {
      throw new QueryValidationException(
          "continuation does not belong to this query structure, ordering, or parameter set");
    }
    if (continuation.mode() == SliceContinuation.Mode.KEYSET) {
      List<@Nullable Object> values = continuation.keysetValues();
      if (values.size() != orderBy.size() || continuation.sqlTypes().size() != orderBy.size()) {
        throw new QueryValidationException("continuation ordering value count does not match");
      }
      for (int index = 0; index < orderBy.size(); index++) {
        SortSpecification<E> sort = orderBy.get(index);
        Object value = values.get(index);
        if (continuation.sqlTypes().get(index) != sort.column().sqlType()
            || continuation.nullMarkers().get(index) != (value == null)
            || (value != null && !sort.column().javaType().isInstance(value))) {
          throw new QueryValidationException(
              "continuation value type does not match ordering property '"
                  + sort.column().property().name()
                  + "'");
        }
      }
    }
  }

  private String queryFingerprint() {
    QueryCompilation<R> structure = compilation(QueryPagination.None.INSTANCE);
    MessageDigest digest = sha256();
    updateDigest(digest, structure.plan().dialectId());
    updateDigest(digest, structure.plan().sql());
    updateDigest(digest, selected.structuralIdentity());
    structure
        .plan()
        .renderedSql()
        .parameters()
        .forEach(
            slot -> {
              updateDigest(digest, Integer.toString(slot.ordinal()));
              updateDigest(digest, slot.javaType().getName());
              updateDigest(digest, slot.sqlType().name());
              updateDigest(digest, slot.nullability().name());
            });
    return HexFormat.of().formatHex(digest.digest());
  }

  private String orderSignature() {
    StringBuilder signature = new StringBuilder();
    for (SortSpecification<E> item : orderBy) {
      signature
          .append(item.column().property().ordinal())
          .append(':')
          .append(item.direction())
          .append(':')
          .append(item.nullPlacement())
          .append(';');
    }
    return signature.isEmpty() ? "unordered" : signature.toString();
  }

  private String parameterDigest() {
    MessageDigest digest = sha256();
    List<Object> arguments = conditionArguments();
    for (Object argument : arguments) {
      updateDigest(digest, argument.getClass().getName());
      updateDigest(digest, deepValue(argument));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void updateDigest(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }

  private static String deepValue(Object value) {
    return value.getClass().isArray()
        ? Arrays.deepToString(new Object[] {value})
        : String.valueOf(value);
  }

  private DefaultSelectQuery<E, R> chain(QueryCondition newPredicate, boolean conjunction) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate == null) {
      throw new QueryValidationException(
          (conjunction ? "and" : "or") + "(...) requires an existing where predicate");
    }
    return copy(
        conjunction ? predicate.and(newPredicate) : predicate.or(newPredicate),
        executionContext,
        orderBy,
        distinct);
  }

  private DefaultSelectQuery<E, R> chainNarrow(
      QueryPredicate<E> newPredicate, boolean conjunction) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate == null) {
      throw new QueryValidationException(
          (conjunction ? "and" : "or") + "(...) requires an existing where predicate");
    }
    QueryCondition combined =
        predicate instanceof QueryPredicate<?> existing
            ? combineNarrow(existing, newPredicate, conjunction)
            : (conjunction ? predicate.and(newPredicate) : predicate.or(newPredicate));
    return copy(combined, executionContext, orderBy, distinct);
  }

  @SuppressWarnings("unchecked")
  private static <E> QueryPredicate<E> combineNarrow(
      QueryPredicate<?> existing, QueryPredicate<E> added, boolean conjunction) {
    QueryPredicate<E> typed = (QueryPredicate<E>) existing;
    return conjunction ? typed.and(added) : typed.or(added);
  }

  private DefaultSelectQuery<E, R> copy(
      @Nullable QueryCondition newPredicate,
      ExecutionContext context,
      List<SortSpecification<E>> newOrderBy,
      boolean newDistinct) {
    return new DefaultSelectQuery<>(
        operations, plans, table, selected, joins, newPredicate, context, newOrderBy, newDistinct);
  }

  private <J> JoinOnStep<E, R, J> joinOn(JoinType type, QueryTable<J> joinedTable) {
    return new DefaultJoinOnStep<>(this, type, Objects.requireNonNull(joinedTable, "table"));
  }

  DefaultSelectQuery<E, R> appendJoin(
      JoinType type, QueryTable<?> joinedTable, @Nullable QueryCondition on) {
    List<QueryJoin> appended = new ArrayList<>(joins.size() + 1);
    appended.addAll(joins);
    appended.add(new QueryJoin(type, joinedTable, on));
    return new DefaultSelectQuery<>(
        operations,
        plans,
        table,
        selected,
        appended,
        predicate,
        executionContext,
        orderBy,
        distinct);
  }

  private List<Object> conditionArguments() {
    if (joins.isEmpty() && predicate == null) {
      return List.of();
    }
    return QueryStructureCompiler.compile(table, joins, predicate).arguments();
  }

  private Object conditionArgument() {
    List<Object> arguments = conditionArguments();
    return arguments.isEmpty() ? NoParameters.INSTANCE : new QueryArguments(arguments);
  }

  private Object fastArgument() {
    return plans.argument(fastPredicate());
  }

  @SuppressWarnings("unchecked")
  private @Nullable QueryPredicate<E> fastPredicate() {
    return predicate == null ? null : (QueryPredicate<E>) predicate;
  }

  private static int sizePlusOne(int pageSize) {
    try {
      return Math.addExact(pageSize, 1);
    } catch (ArithmeticException exception) {
      throw new QueryValidationException("pageSize + 1 overflows int", exception);
    }
  }

  private static long nextOffset(long offset, int pageSize) {
    try {
      return Math.addExact(offset, pageSize);
    } catch (ArithmeticException exception) {
      throw new QueryValidationException("slice continuation offset overflows long", exception);
    }
  }

  private static <T> List<@Nullable T> visibleRows(List<@Nullable T> rows, int pageSize) {
    List<@Nullable T> items = new ArrayList<>(Math.min(rows.size(), pageSize));
    items.addAll(rows.subList(0, Math.min(rows.size(), pageSize)));
    return items;
  }

  @SuppressWarnings("unchecked")
  private static <T> Slice<T> requireNonNullSlice(Slice<@Nullable T> slice) {
    for (int index = 0; index < slice.items().size(); index++) {
      Objects.requireNonNull(slice.items().get(index), "non-null query produced a null slice item");
    }
    // Slice is immutable, so this checked nullable-to-non-null narrowing remains safe.
    return (Slice<T>) (Slice<?>) slice;
  }

  @SuppressWarnings("unchecked")
  private static <T> Page<T> requireNonNullPage(Page<@Nullable T> page) {
    requireNonNullValues(page.items(), "non-null query produced a null page item");
    // Page is immutable, so this checked nullable-to-non-null narrowing remains safe.
    return (Page<T>) (Page<?>) page;
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> requireNonNullValues(List<@Nullable T> values, String message) {
    for (T value : values) {
      Objects.requireNonNull(value, message);
    }
    return (List<T>) (List<?>) values;
  }

  private record PaginationShape(String mode, List<Boolean> nullMarkers) {

    private static PaginationShape of(QueryPagination pagination) {
      return switch (pagination) {
        case QueryPagination.None ignored -> new PaginationShape("none", List.of());
        case QueryPagination.LimitOnly ignored -> new PaginationShape("limit", List.of());
        case QueryPagination.Offset ignored -> new PaginationShape("offset", List.of());
        case QueryPagination.Keyset keyset ->
            new PaginationShape("keyset", keyset.values().stream().map(Objects::isNull).toList());
      };
    }
  }

  private record CachedPlan<T>(CompiledQueryPlan<T, Object> plan, StatementAst ast) {

    private CachedPlan {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(ast, "ast");
    }
  }

  private static final class LocalPlanCache<T> {

    private static final int MAXIMUM_SHAPES = 32;
    private final LinkedHashMap<PaginationShape, CachedPlan<T>> plans =
        new LinkedHashMap<>(8, 0.75F, true);

    private synchronized QueryCompilation<T> getOrCompile(
        QueryPagination pagination, Supplier<QueryCompilation<T>> compiler, Object argument) {
      PaginationShape shape = PaginationShape.of(pagination);
      CachedPlan<T> existing = plans.get(shape);
      if (existing != null) {
        return new QueryCompilation<>(existing.plan(), argument, existing.ast());
      }
      QueryCompilation<T> compiled = Objects.requireNonNull(compiler.get(), "compiled query");
      plans.put(shape, new CachedPlan<>(compiled.plan(), compiled.ast()));
      if (plans.size() > MAXIMUM_SHAPES) {
        var entries = plans.entrySet().iterator();
        entries.next();
        entries.remove();
      }
      return compiled;
    }
  }
}
