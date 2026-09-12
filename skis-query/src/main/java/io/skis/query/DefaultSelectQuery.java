package io.skis.query;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.jdbc.JdbcPageResult;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.TableOccurrence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Built-in unified immutable query implementation. */
final class DefaultSelectQuery<E, R> implements SelectQuery<E, R> {

  private final DefaultQueryOperations operations;
  private final EntityPlanSet<E> plans;
  private final SelectQueryState<R> state;
  private final QueryParameters parameters;
  private final ExecutionContext executionContext;
  private volatile @Nullable QueryAnalysis analysis;
  private final AtomicReference<@Nullable CachedPlan<R>> fastPlan = new AtomicReference<>();
  private final LocalPlanCache<R> plansByPagination = new LocalPlanCache<>();
  private final LocalPlanCache<OrderedRow<R>> orderedPlansByPagination = new LocalPlanCache<>();
  private final AtomicReference<@Nullable CachedPlan<Long>> countPlan = new AtomicReference<>();

  static <E, R> DefaultSelectQuery<E, R> create(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      SelectedResult<R> selected) {
    return new DefaultSelectQuery<>(
        operations,
        plans,
        SelectQueryState.create(selected, table),
        QueryParameters.empty(),
        ExecutionContext.EMPTY);
  }

  static <E, R> DefaultSelectQuery<E, R> create(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      SelectQueryState<R> state,
      QueryParameters parameters) {
    return new DefaultSelectQuery<>(operations, plans, state, parameters, ExecutionContext.EMPTY);
  }

  private DefaultSelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      SelectQueryState<R> state,
      QueryParameters parameters,
      ExecutionContext executionContext) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.state = Objects.requireNonNull(state, "state");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
  }

  @Override
  public DefaultSelectQuery<E, R> where(QueryCondition newPredicate) {
    Objects.requireNonNull(newPredicate, "predicate");
    return copy(
        state.where(QueryConditions.structure(newPredicate)),
        parameters.merge(QueryConditions.parameters(newPredicate)),
        executionContext);
  }

  @Override
  public DefaultSelectQuery<E, R> and(QueryCondition newPredicate) {
    return chain(newPredicate, true);
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
        : copy(state, parameters, context);
  }

  @Override
  public DefaultSelectQuery<E, R> orderBy(SortSpecification... specifications) {
    Objects.requireNonNull(specifications, "specifications");
    SelectQueryState<R> replacement =
        state.orderBy(List.copyOf(Arrays.asList(specifications.clone())));
    return replacement == state ? this : copy(replacement, parameters, executionContext);
  }

  @Override
  public DefaultSelectQuery<E, R> thenByPrimaryKey(SortDirection direction) {
    SelectQueryState<R> replacement = state.thenByPrimaryKey(direction);
    return replacement == state ? this : copy(replacement, parameters, executionContext);
  }

  @Override
  public DefaultSelectQuery<E, R> distinct() {
    SelectQueryState<R> replacement = state.distinctResult();
    return replacement == state ? this : copy(replacement, parameters, executionContext);
  }

  @Override
  public CountQuery countQuery() {
    return new DefaultCountQuery(operations, this);
  }

  @Override
  public Optional<R> fetchOne() {
    if (isFastPathShape(QueryPagination.None.INSTANCE)) {
      return operations.fetchOne(fastPlan().plan(), fastArgument(), executionContext);
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
      return operations.fetchList(fastPlan().plan(), fastArgument(), executionContext);
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
    SelectQueryState<R> finalState = state.withSqlPagination(pagination);
    QueryAnalysis queryAnalysis = analysis();
    return plansByPagination.getOrCompile(
        finalState.sqlPagination(),
        () ->
            plans
                .compiler()
                .compileSelection(
                    plans.model(),
                    table(),
                    finalState.selected(),
                    queryAnalysis.structure(),
                    finalState.orderBy(),
                    finalState.distinct(),
                    pagination,
                    List.of(),
                    queryAnalysis.arguments()),
        paginationArgument(queryAnalysis, pagination));
  }

  ExecutionContext executionContext() {
    return executionContext;
  }

  private QueryCompilation<OrderedRow<R>> orderedCompilation(QueryPagination pagination) {
    validateDistinctOrdering();
    SelectQueryState<R> finalState = state.withSqlPagination(pagination);
    QueryAnalysis queryAnalysis = analysis();
    return orderedPlansByPagination.getOrCompile(
        finalState.sqlPagination(),
        () ->
            plans
                .compiler()
                .compileOrdered(
                    plans.model(),
                    table(),
                    finalState.selected(),
                    queryAnalysis.structure(),
                    finalState.orderBy(),
                    finalState.distinct(),
                    pagination,
                    queryAnalysis.arguments()),
        paginationArgument(queryAnalysis, pagination));
  }

  private boolean isFastPathShape(QueryPagination pagination) {
    return pagination == QueryPagination.None.INSTANCE
        && state.joins().isEmpty()
        && state.selected().belongsTo(table())
        && state.selected().supportsFastPath()
        && state.groupBy().isEmpty()
        && state.having() == null
        && state.orderBy().isEmpty()
        && !state.distinct();
  }

  private QueryCompilation<R> unpaginatedCompilation() {
    QueryAnalysis queryAnalysis = analysis();
    CachedPlan<R> cached = fastPlan();
    return new QueryCompilation<>(cached.plan(), queryAnalysis.argument(), cached.ast());
  }

  private CachedPlan<R> fastPlan() {
    CachedPlan<R> existing = fastPlan.get();
    if (existing != null) {
      return existing;
    }
    QueryAnalysis queryAnalysis = analysis();
    CompiledQueryPlan<R, Object> plan = state.selected().fastPlan(plans, queryAnalysis.structure());
    SelectStatement ast =
        new SelectStatement(
            state.selected().expressions(),
            queryAnalysis.structure().fromClause(),
            queryAnalysis.structure().where());
    CachedPlan<R> compiled = new CachedPlan<>(plan, ast);
    CachedPlan<R> published = fastPlan.compareAndExchange(null, compiled);
    return published == null ? compiled : published;
  }

  QueryCompilation<Long> countCompilation() {
    QueryAnalysis queryAnalysis = analysis();
    CachedPlan<Long> existing = countPlan.get();
    if (existing != null) {
      return new QueryCompilation<>(existing.plan(), queryAnalysis.argument(), existing.ast());
    }
    QueryCompilation<Long> compiled =
        plans
            .compiler()
            .compileCount(
                plans.model(),
                table(),
                state.selected(),
                queryAnalysis.structure(),
                state.distinct(),
                queryAnalysis.arguments());
    CachedPlan<Long> cached = new CachedPlan<>(compiled.plan(), compiled.ast());
    CachedPlan<Long> published = countPlan.compareAndExchange(null, cached);
    CachedPlan<Long> effective = published == null ? cached : published;
    return new QueryCompilation<>(effective.plan(), queryAnalysis.argument(), effective.ast());
  }

  private Object paginationArgument(QueryAnalysis queryAnalysis, QueryPagination pagination) {
    if (pagination == QueryPagination.None.INSTANCE) {
      return queryAnalysis.argument();
    }
    List<@Nullable Object> arguments = new ArrayList<>(queryAnalysis.arguments());
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
              state.orderBy().stream().map(item -> item.selectable().sqlType()).toList(),
              nullMarkers,
              anchor.orderValues(),
              parameterDigest());
    }
    return Slice.of(items, pageSize, next);
  }

  private void validatePaginationOrder(boolean keyset) {
    if (state.orderBy().isEmpty()) {
      throw new QueryValidationException("pagination requires explicit stable ORDER BY");
    }
    CompiledQueryStructure structure = analysis().structure();
    validateOrderScope(structure.fromClause());
    if (state.distinct()) {
      if (!hasStableDistinctOrdering()) {
        SqlExpression<?> missing =
            state.selected().expressions().stream()
                .filter(
                    expression ->
                        state.orderBy().stream()
                            .noneMatch(item -> item.expression().equals(expression)))
                .findFirst()
                .orElseThrow();
        throw new QueryValidationException(
            "distinct pagination ORDER BY must cover every selected expression; missing '"
                + expressionSummary(missing)
                + "'");
      }
    } else {
      validateOccurrencePrimaryKeys(structure.fromClause());
    }
    if (keyset) {
      for (SortSpecification item : state.orderBy()) {
        QueryColumn<?, ?> column = requirePhysicalPaginationColumn(item);
        if (structure.fromClause().effectiveNullability(column.expression()).isNullable()
            && item.nullPlacement() == NullPlacement.DIALECT_DEFAULT) {
          throw new QueryValidationException(
              "effectively nullable keyset ordering property '"
                  + column.property().name()
                  + "' must declare nullsFirst() or nullsLast()");
        }
      }
    }
  }

  private boolean hasStableDistinctOrdering() {
    if (!state.distinct()) {
      return false;
    }
    List<SqlExpression<?>> expressions = state.selected().expressions();
    return expressions.stream()
        .allMatch(
            expression ->
                state.orderBy().stream().anyMatch(item -> item.expression().equals(expression)));
  }

  private void validateOrderScope(FromClause fromClause) {
    for (SortSpecification item : state.orderBy()) {
      SqlExpression<?> expression = item.expression();
      if (expression instanceof ColumnExpression<?, ?> column
          && fromClause.occurrenceOf(column.table()).isEmpty()) {
        throw new QueryValidationException(
            "ORDER BY expression '"
                + expressionSummary(expression)
                + "' is not visible in the final FROM/JOIN scope");
      }
    }
  }

  private void validateOccurrencePrimaryKeys(FromClause fromClause) {
    for (TableOccurrence occurrence : fromClause.occurrences()) {
      TableExpression<?> occurrenceTable = entityTable(occurrence);
      PrimaryKeyMeta<?> primaryKey =
          occurrenceTable
              .entity()
              .primaryKey()
              .orElseThrow(
                  () ->
                      new QueryValidationException(
                          "pagination ORDER BY requires primary-key metadata for "
                              + occurrenceDescription(occurrence)));
      for (PropertyMeta<?, ?> property : primaryKey.properties()) {
        boolean present =
            state.orderBy().stream()
                .anyMatch(
                    item -> {
                      Selectable<?> selectable = item.selectable();
                      return selectable instanceof QueryColumn<?, ?> column
                          && column.table() == occurrenceTable
                          && column.property() == property;
                    });
        if (!present) {
          throw new QueryValidationException(
              "pagination ORDER BY is not stable for "
                  + occurrenceDescription(occurrence)
                  + "; missing primary-key property '"
                  + property.name()
                  + "'");
        }
      }
    }
  }

  private void validateDistinctOrdering() {
    if (!state.distinct() || state.orderBy().isEmpty()) {
      return;
    }
    List<SqlExpression<?>> expressions = state.selected().expressions();
    for (SortSpecification item : state.orderBy()) {
      if (!expressions.contains(item.expression())) {
        throw new QueryValidationException(
            "distinct ORDER BY expression '"
                + SelectableSupport.summary(item.selectable())
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
      if (values.size() != state.orderBy().size()
          || continuation.sqlTypes().size() != state.orderBy().size()) {
        throw new QueryValidationException("continuation ordering value count does not match");
      }
      for (int index = 0; index < state.orderBy().size(); index++) {
        SortSpecification sort = state.orderBy().get(index);
        Object value = values.get(index);
        if (continuation.sqlTypes().get(index) != sort.selectable().sqlType()
            || continuation.nullMarkers().get(index) != (value == null)
            || (value != null && !sort.selectable().javaType().isInstance(value))) {
          throw new QueryValidationException(
              "continuation value type does not match ordering expression '"
                  + SelectableSupport.summary(sort.selectable())
                  + "'");
        }
      }
    }
  }

  String queryFingerprint() {
    QueryCompilation<R> structure = compilation(QueryPagination.None.INSTANCE);
    FromClause fromClause = analysis().structure().fromClause();
    MessageDigest digest = sha256();
    updateDigest(digest, "join-query-fingerprint-v1");
    updateDigest(digest, structure.plan().dialectId());
    updateDigest(digest, structure.plan().sql());
    updateDigest(digest, state.selected().structuralIdentity());
    for (TableOccurrence occurrence : fromClause.occurrences()) {
      TableExpression<?> occurrenceTable = entityTable(occurrence);
      var entity = occurrenceTable.entity();
      var physicalTable = entity.table();
      updateDigest(digest, Integer.toString(occurrence.occurrenceOrdinal()));
      updateDigest(digest, entity.javaType().getName());
      updateDigest(digest, entity.entityName());
      updateDigest(digest, entity.mode().name());
      updateDigest(digest, physicalTable.catalog());
      updateDigest(digest, physicalTable.schema());
      updateDigest(digest, physicalTable.name());
      updateDigest(digest, occurrenceTable.alias().map(Identifier::value).orElse("<unaliased>"));
    }
    fromClause.joins().forEach(join -> updateDigest(digest, join.type().name()));
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

  String orderSignature() {
    FromClause fromClause = analysis().structure().fromClause();
    StringBuilder signature = new StringBuilder();
    for (SortSpecification item : state.orderBy()) {
      QueryColumn<?, ?> column = requirePhysicalPaginationColumn(item);
      TableOccurrence occurrence =
          fromClause
              .occurrenceOf(column.table())
              .orElseThrow(
                  () ->
                      new QueryValidationException(
                          "ORDER BY expression '"
                              + expressionSummary(item.expression())
                              + "' is not visible in the final FROM/JOIN scope"));
      signature
          .append(occurrence.occurrenceOrdinal())
          .append(':')
          .append(occurrence.effectiveQualifier())
          .append(':')
          .append(entityTable(occurrence).entity().javaType().getName())
          .append(':')
          .append(column.property().ordinal())
          .append(':')
          .append(column.property().name())
          .append(':')
          .append(item.selectable().javaType().getName())
          .append(':')
          .append(item.selectable().sqlType())
          .append(':')
          .append(item.direction())
          .append(':')
          .append(item.nullPlacement())
          .append(';');
    }
    return signature.isEmpty() ? "unordered" : signature.toString();
  }

  private static String occurrenceDescription(TableOccurrence occurrence) {
    return "table occurrence #"
        + occurrence.occurrenceOrdinal()
        + " with effective qualifier '"
        + occurrence.effectiveQualifier()
        + "' and entity '"
        + entityTable(occurrence).entity().entityName()
        + "'";
  }

  private static TableExpression<?> entityTable(TableOccurrence occurrence) {
    return occurrence
        .entityTable()
        .orElseThrow(
            () ->
                new QueryValidationException(
                    "relation occurrence #"
                        + occurrence.occurrenceOrdinal()
                        + " is not backed by an entity table"));
  }

  private static String expressionSummary(SqlExpression<?> expression) {
    if (expression instanceof ColumnExpression<?, ?> column) {
      String qualifier =
          column
              .table()
              .alias()
              .map(Identifier::value)
              .orElse(column.table().entity().table().name());
      return qualifier + '.' + column.property().name();
    }
    return expression.getClass().getSimpleName();
  }

  private String parameterDigest() {
    MessageDigest digest = sha256();
    List<@Nullable Object> arguments = conditionArguments();
    for (@Nullable Object argument : arguments) {
      if (argument == null) {
        updateDigest(digest, "<null>");
      } else {
        updateDigest(digest, argument.getClass().getName());
        updateDigest(digest, deepValue(argument));
      }
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
    QueryCondition structure = QueryConditions.structure(newPredicate);
    return copy(
        state.chainWhere(structure, conjunction),
        parameters.merge(QueryConditions.parameters(newPredicate)),
        executionContext);
  }

  private DefaultSelectQuery<E, R> copy(
      SelectQueryState<R> replacement,
      QueryParameters replacementParameters,
      ExecutionContext context) {
    return replacement == state
            && replacementParameters == parameters
            && context == executionContext
        ? this
        : new DefaultSelectQuery<>(operations, plans, replacement, replacementParameters, context);
  }

  private <J> JoinOnStep<E, R, J> joinOn(JoinType type, QueryTable<J> joinedTable) {
    return new DefaultJoinOnStep<>(this, type, Objects.requireNonNull(joinedTable, "table"));
  }

  DefaultSelectQuery<E, R> appendJoin(
      JoinType type, QueryTable<?> joinedTable, @Nullable QueryCondition on) {
    QueryCondition structure = on == null ? null : QueryConditions.structure(on);
    QueryParameters appendedParameters =
        on == null ? parameters : parameters.merge(QueryConditions.parameters(on));
    return copy(
        state.appendJoin(type, joinedTable, structure), appendedParameters, executionContext);
  }

  private List<@Nullable Object> conditionArguments() {
    return analysis().arguments();
  }

  private Object fastArgument() {
    return analysis().argument();
  }

  private QueryAnalysis analysis() {
    QueryAnalysis existing = analysis;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      existing = analysis;
      if (existing == null) {
        CompiledQueryStructure structure = state.structure();
        List<@Nullable Object> arguments = structure.arguments(parameters);
        Object argument =
            arguments.isEmpty() ? NoParameters.INSTANCE : new QueryArguments(arguments);
        existing = new QueryAnalysis(structure, argument);
        analysis = existing;
      }
      return existing;
    }
  }

  private QueryTable<E> table() {
    return state.typedRoot();
  }

  private static QueryColumn<?, ?> requirePhysicalPaginationColumn(SortSpecification item) {
    if (item.selectable() instanceof QueryColumn<?, ?> column) {
      return column;
    }
    throw new QueryValidationException(
        "pagination identity analysis does not yet support ORDER BY expression '"
            + SelectableSupport.summary(item.selectable())
            + "'");
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

  private record QueryAnalysis(CompiledQueryStructure structure, Object argument) {

    private QueryAnalysis {
      Objects.requireNonNull(structure, "structure");
      Objects.requireNonNull(argument, "argument");
    }

    private List<@Nullable Object> arguments() {
      return argument instanceof QueryArguments(List<@Nullable Object> values) ? values : List.of();
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
    private final LinkedHashMap<SqlPaginationStructure, CachedPlan<T>> plans =
        new LinkedHashMap<>(8, 0.75F, true);

    private synchronized QueryCompilation<T> getOrCompile(
        SqlPaginationStructure pagination,
        Supplier<QueryCompilation<T>> compiler,
        Object argument) {
      CachedPlan<T> existing = plans.get(pagination);
      if (existing != null) {
        return new QueryCompilation<>(existing.plan(), argument, existing.ast());
      }
      QueryCompilation<T> compiled = Objects.requireNonNull(compiler.get(), "compiled query");
      plans.put(pagination, new CachedPlan<>(compiled.plan(), compiled.ast()));
      if (plans.size() > MAXIMUM_SHAPES) {
        var entries = plans.entrySet().iterator();
        entries.next();
        entries.remove();
      }
      return new QueryCompilation<>(compiled.plan(), argument, compiled.ast());
    }
  }
}
