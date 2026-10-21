package io.skis.query.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.StatementPurpose;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.internal.JdbcPageResult;
import io.skis.jdbc.internal.JdbcRow;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.query.CountQuery;
import io.skis.query.DerivedRelation;
import io.skis.query.JoinOnStep;
import io.skis.query.NullPlacement;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryCondition;
import io.skis.query.QueryCursor;
import io.skis.query.QueryParameter;
import io.skis.query.QueryTable;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
import io.skis.query.SingleRow;
import io.skis.query.Slice;
import io.skis.query.SliceContinuation;
import io.skis.query.SliceRequest;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.DerivedColumnExpression;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Built-in unified immutable query implementation. */
final class DefaultSelectQuery<R extends @Nullable Object> implements SelectQuery<R> {

  private final DefaultQueryOperations operations;
  private final QueryPlanCompiler compiler;
  private final @Nullable EntityPlanSet<?> fastPlans;
  private final SelectQueryState<R> state;
  private final QueryParameters parameters;
  private final ExecutionContext executionContext;
  private volatile @Nullable QueryAnalysis analysis;
  private volatile @Nullable QueryAnalysis countAnalysis;
  private final AtomicReference<@Nullable CachedPlan<R>> fastPlan = new AtomicReference<>();
  private final LocalPlanCache<R> plansByPagination = new LocalPlanCache<>();
  private final LocalPlanCache<OrderedRow<R>> orderedPlansByPagination = new LocalPlanCache<>();
  private final AtomicReference<@Nullable CachedPlan<Long>> countPlan = new AtomicReference<>();

  static <R extends @Nullable Object> DefaultSelectQuery<R> create(
      DefaultQueryOperations operations,
      EntityPlanSet<?> plans,
      QueryTable<?> table,
      SelectedResult<R> selected) {
    return new DefaultSelectQuery<>(
        operations,
        plans.compiler(),
        plans,
        SelectQueryState.create(selected, table),
        QueryParameters.empty(),
        ExecutionContext.EMPTY);
  }

  static <R extends @Nullable Object> DefaultSelectQuery<R> create(
      DefaultQueryOperations operations,
      EntityPlanSet<?> plans,
      SelectQueryState<R> state,
      QueryParameters parameters) {
    return new DefaultSelectQuery<>(
        operations, plans.compiler(), plans, state, parameters, ExecutionContext.EMPTY);
  }

  static <R extends @Nullable Object> DefaultSelectQuery<R> create(
      DefaultQueryOperations operations,
      QueryPlanCompiler compiler,
      SelectQueryState<R> state,
      QueryParameters parameters) {
    return new DefaultSelectQuery<>(
        operations, compiler, null, state, parameters, ExecutionContext.EMPTY);
  }

  private DefaultSelectQuery(
      DefaultQueryOperations operations,
      QueryPlanCompiler compiler,
      @Nullable EntityPlanSet<?> fastPlans,
      SelectQueryState<R> state,
      QueryParameters parameters,
      ExecutionContext executionContext) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    this.fastPlans = fastPlans;
    this.state = Objects.requireNonNull(state, "state");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
  }

  @Override
  public DefaultSelectQuery<R> where(QueryCondition newPredicate) {
    Objects.requireNonNull(newPredicate, "predicate");
    return copy(
        state.where(QueryConditions.structure(newPredicate)),
        parameters.merge(QueryConditions.parameters(newPredicate)),
        executionContext);
  }

  @Override
  public DefaultSelectQuery<R> and(QueryCondition newPredicate) {
    return chain(newPredicate, true);
  }

  @Override
  public DefaultSelectQuery<R> or(QueryCondition newPredicate) {
    return chain(newPredicate, false);
  }

  @Override
  public JoinOnStep<SelectQuery<R>> join(QueryTable<?> joinedTable) {
    return innerJoin(joinedTable);
  }

  @Override
  public JoinOnStep<SelectQuery<R>> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  @Override
  public JoinOnStep<SelectQuery<R>> innerJoin(QueryTable<?> joinedTable) {
    return joinOn(JoinType.INNER, QueryRelations.of(joinedTable));
  }

  @Override
  public JoinOnStep<SelectQuery<R>> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SelectQuery<R>> leftJoin(QueryTable<?> joinedTable) {
    return joinOn(JoinType.LEFT, QueryRelations.of(joinedTable));
  }

  @Override
  public JoinOnStep<SelectQuery<R>> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SelectQuery<R>> rightJoin(QueryTable<?> joinedTable) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(joinedTable));
  }

  @Override
  public JoinOnStep<SelectQuery<R>> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SelectQuery<R>> fullJoin(QueryTable<?> joinedTable) {
    return joinOn(JoinType.FULL, QueryRelations.of(joinedTable));
  }

  @Override
  public JoinOnStep<SelectQuery<R>> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, QueryRelations.of(relation));
  }

  @Override
  public DefaultSelectQuery<R> crossJoin(QueryTable<?> joinedTable) {
    return appendJoin(JoinType.CROSS, QueryRelations.of(joinedTable), null);
  }

  @Override
  public DefaultSelectQuery<R> crossJoin(DerivedRelation relation) {
    return appendJoin(JoinType.CROSS, QueryRelations.of(relation), null);
  }

  @Override
  public DefaultSelectQuery<R> withOptions(ExecutionOptions executionOptions) {
    ExecutionContext context =
        ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions"));
    return executionContext.executionOptions().equals(context.executionOptions())
        ? this
        : copy(state, parameters, context);
  }

  @Override
  public DefaultSelectQuery<R> orderBy(SortSpecification... specifications) {
    Objects.requireNonNull(specifications, "specifications");
    SelectQueryState<R> replacement =
        state.orderBy(List.copyOf(Arrays.asList(specifications.clone())));
    return replacement == state ? this : copy(replacement, parameters, executionContext);
  }

  @Override
  public DefaultSelectQuery<R> thenByPrimaryKey(SortDirection direction) {
    SelectQueryState<R> replacement = state.thenByPrimaryKey(direction);
    return replacement == state ? this : copy(replacement, parameters, executionContext);
  }

  @Override
  public DefaultSelectQuery<R> distinct() {
    SelectQueryState<R> replacement = state.distinctResult();
    return replacement == state ? this : copy(replacement, parameters, executionContext);
  }

  @Override
  public <V> DefaultSelectQuery<R> bind(QueryParameter<V> parameter, @Nullable V value) {
    QueryParameter<V> reference = Objects.requireNonNull(parameter, "parameter");
    requireDeclaredParameter(reference);
    return copy(state, parameters.bind(reference, value), executionContext);
  }

  @Override
  public CountQuery countQuery() {
    return new DefaultCountQuery(operations, this);
  }

  @Override
  public SingleRow<R> fetchOne() {
    OperationObservation observation =
        operations.startObservation(ExecutionOperationKind.FETCH_ONE);
    try {
      JdbcRow<R> row;
      if (isFastPathShape(QueryPagination.None.INSTANCE)) {
        row =
            operations.fetchNullableOne(
                fastPlan(observation, StatementPurpose.PRIMARY).plan(),
                fastArgument(),
                executionContext,
                observation,
                StatementPurpose.PRIMARY);
      } else {
        QueryCompilation<R> query =
            compilation(QueryPagination.None.INSTANCE, observation, StatementPurpose.PRIMARY);
        row =
            operations.fetchNullableOne(
                query.plan(),
                query.argument(),
                executionContext,
                observation,
                StatementPurpose.PRIMARY);
      }
      SingleRow<R> result = singleRow(row);
      operations.completeSucceeded(observation);
      return result;
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
  }

  @Override
  public SingleRow<R> fetchFirst() {
    OperationObservation observation =
        operations.startObservation(ExecutionOperationKind.FETCH_FIRST);
    try {
      QueryCompilation<R> query =
          compilation(new QueryPagination.LimitOnly(1), observation, StatementPurpose.PRIMARY);
      SingleRow<R> result =
          singleRow(
              operations.fetchNullableFirst(
                  query.plan(),
                  query.argument(),
                  executionContext,
                  observation,
                  StatementPurpose.PRIMARY));
      operations.completeSucceeded(observation);
      return result;
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
  }

  @Override
  public List<R> fetchList() {
    OperationObservation observation =
        operations.startObservation(ExecutionOperationKind.FETCH_LIST);
    try {
      List<R> values;
      if (isFastPathShape(QueryPagination.None.INSTANCE)) {
        values =
            operations.fetchList(
                fastPlan(observation, StatementPurpose.PRIMARY).plan(),
                fastArgument(),
                executionContext,
                observation,
                StatementPurpose.PRIMARY);
      } else {
        QueryCompilation<R> query =
            compilation(QueryPagination.None.INSTANCE, observation, StatementPurpose.PRIMARY);
        values =
            operations.fetchList(
                query.plan(),
                query.argument(),
                executionContext,
                observation,
                StatementPurpose.PRIMARY);
      }
      requireAllowedNulls(values, "non-null query produced a null list item");
      operations.completeSucceeded(observation);
      return values;
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
  }

  @Override
  public Page<R> fetchPage(PageRequest request) {
    OperationObservation observation =
        operations.startObservation(ExecutionOperationKind.FETCH_PAGE);
    try {
      validatePageRequest(request);
      Page<R> result = fetchPageResult(request, countCompilation(observation), observation);
      operations.completeSucceeded(observation);
      return result;
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
  }

  @Override
  public Page<R> fetchPage(PageRequest request, CountQuery explicitCountQuery) {
    OperationObservation observation =
        operations.startObservation(ExecutionOperationKind.FETCH_PAGE);
    try {
      validatePageRequest(request);
      Page<R> result =
          fetchPageResult(
              request,
              requireExplicitCount(
                  Objects.requireNonNull(explicitCountQuery, "explicitCountQuery"), observation),
              observation);
      operations.completeSucceeded(observation);
      return result;
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
  }

  private Page<R> fetchPageResult(
      PageRequest request,
      QueryCompilation<Long> count,
      @Nullable OperationObservation observation) {
    QueryCompilation<R> content =
        compilation(QueryPagination.page(request), observation, StatementPurpose.PAGE_CONTENT);
    JdbcPageResult<R> result = operations.fetchPage(content, count, executionContext, observation);
    requireAllowedNulls(result.items(), "non-null query produced a null page item");
    return Page.of(result.items(), request, result.totalElements());
  }

  private void validatePageRequest(PageRequest request) {
    Objects.requireNonNull(request, "request");
    validatePaginationOrder(false);
    operations.validateRequestedRows(request.pageSize(), executionContext);
  }

  @Override
  public Slice<R> fetchSlice(SliceRequest request) {
    OperationObservation observation =
        operations.startObservation(ExecutionOperationKind.FETCH_SLICE);
    try {
      DefaultSliceRequest sliceRequest =
          QueryModels.sliceRequest(Objects.requireNonNull(request, "request"));
      validatePaginationOrder(false);
      // Every Slice may need a continuation, including its first offset page.
      for (SortSpecification item : state.orderBy()) {
        requirePhysicalPaginationColumn(item);
      }
      operations.validateRequestedRows(sliceRequest.pageSize(), executionContext);
      Slice<R> result =
          switch (sliceRequest.mode()) {
            case OFFSET ->
                fetchOffsetSlice(sliceRequest.offset(), sliceRequest.pageSize(), observation);
            case KEYSET_FIRST -> fetchKeysetSlice(null, sliceRequest.pageSize(), observation);
            case RESUME ->
                resumeSlice(sliceRequest.continuation(), sliceRequest.pageSize(), observation);
          };
      operations.completeSucceeded(observation);
      return result;
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
  }

  @Override
  public QueryCursor<R> cursor() {
    return openResource(ExecutionOperationKind.CURSOR);
  }

  @Override
  public Stream<R> stream() {
    return QueryStreams.open(openResource(ExecutionOperationKind.STREAM));
  }

  private QueryCursor<R> openResource(ExecutionOperationKind operationKind) {
    OperationObservation observation = operations.startObservation(operationKind);
    try {
      QueryCompilation<R> query =
          compilation(QueryPagination.None.INSTANCE, observation, StatementPurpose.PRIMARY);
      return operations.cursor(
          query.plan(),
          query.argument(),
          executionContext,
          state.selected().nullableResult(),
          observation,
          StatementPurpose.PRIMARY);
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
  }

  QueryCompilation<R> compilation(QueryPagination pagination) {
    return compilation(pagination, null, StatementPurpose.PRIMARY);
  }

  private QueryCompilation<R> compilation(
      QueryPagination pagination,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    state.validateDistinctOrdering();
    if (isFastPathShape(pagination)) {
      return unpaginatedCompilation(observation, purpose);
    }
    SelectQueryState<R> finalState = state.withSqlPagination(pagination);
    QueryAnalysis queryAnalysis = analysis();
    QueryArguments statementArguments = queryAnalysis.arguments().withPagination(pagination);
    return plansByPagination.getOrCompile(
        finalState.sqlPagination(),
        () ->
            compiler.compileSelection(
                finalState.selected(),
                queryAnalysis.structure(),
                finalState.orderBy(),
                finalState.distinct(),
                pagination,
                List.of(),
                statementArguments,
                observation,
                purpose),
        statementArguments.planArgument());
  }

  ExecutionContext executionContext() {
    return executionContext;
  }

  private QueryCompilation<OrderedRow<R>> orderedCompilation(
      QueryPagination pagination, @Nullable OperationObservation observation) {
    state.validateDistinctOrdering();
    SelectQueryState<R> finalState = state.withSqlPagination(pagination);
    QueryAnalysis queryAnalysis = analysis();
    QueryArguments statementArguments = queryAnalysis.arguments().withPagination(pagination);
    return orderedPlansByPagination.getOrCompile(
        finalState.sqlPagination(),
        () ->
            compiler.compileOrdered(
                finalState.selected(),
                queryAnalysis.structure(),
                finalState.orderBy(),
                finalState.distinct(),
                pagination,
                statementArguments,
                observation,
                StatementPurpose.PRIMARY),
        statementArguments.planArgument());
  }

  private boolean isFastPathShape(QueryPagination pagination) {
    QueryTable<?> entityRoot = state.entityRootOrNull();
    return pagination == QueryPagination.None.INSTANCE
        && entityRoot != null
        && fastPlans != null
        && fastPlans.model().entity() == entityRoot.entity()
        && state.joins().isEmpty()
        && state.selected().belongsTo(entityRoot)
        && state.selected().supportsFastPath()
        && state.groupBy().isEmpty()
        && state.having() == null
        && state.orderBy().isEmpty()
        && !state.distinct();
  }

  private QueryCompilation<R> unpaginatedCompilation(
      @Nullable OperationObservation observation, StatementPurpose purpose) {
    QueryAnalysis queryAnalysis = analysis();
    CachedPlan<R> cached = fastPlan(observation, purpose);
    return new QueryCompilation<>(
        cached.plan(), queryAnalysis.arguments().planArgument(), cached.ast());
  }

  private CachedPlan<R> fastPlan(
      @Nullable OperationObservation observation, StatementPurpose purpose) {
    CachedPlan<R> existing = fastPlan.get();
    if (existing != null) {
      return existing;
    }
    QueryAnalysis queryAnalysis = analysis();
    EntityPlanSet<?> entityPlans = Objects.requireNonNull(fastPlans, "fast-path entity plans");
    CompiledQueryPlan<R, Object> plan =
        state.selected().fastPlan(entityPlans, queryAnalysis.structure(), observation, purpose);
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
    return countCompilation(null);
  }

  QueryCompilation<Long> countCompilation(@Nullable OperationObservation observation) {
    QueryAnalysis queryAnalysis = countAnalysis();
    CompiledQueryStructure countStructure = queryAnalysis.structure();
    QueryArguments countArguments = queryAnalysis.arguments();
    Object countArgument = countArguments.planArgument();
    CachedPlan<Long> existing = countPlan.get();
    if (existing != null) {
      return new QueryCompilation<>(existing.plan(), countArgument, existing.ast());
    }
    QueryCompilation<Long> compiled =
        compiler.compileCount(
            state.selected(),
            countStructure,
            state.distinct(),
            countArguments,
            observation,
            StatementPurpose.PAGE_COUNT);
    CachedPlan<Long> cached = new CachedPlan<>(compiled.plan(), compiled.ast());
    CachedPlan<Long> published = countPlan.compareAndExchange(null, cached);
    if (published == null) {
      return compiled;
    }
    return new QueryCompilation<>(published.plan(), countArgument, published.ast());
  }

  private QueryCompilation<Long> requireExplicitCount(
      CountQuery explicitCountQuery, @Nullable OperationObservation observation) {
    if (!(explicitCountQuery instanceof DefaultCountQuery countQuery)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_COUNT_CONTRACT,
          "explicit count must be a built-in SKIS CountQuery created with countQuery()");
    }
    if (countQuery.operations() != operations) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_COUNT_CONTRACT,
          "explicit count must use the same execution and data-source scope as the content query");
    }
    if (!countQuery
        .executionContext()
        .executionOptions()
        .equals(executionContext.executionOptions())) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_COUNT_CONTRACT,
          "explicit count must use the same execution options as the content query");
    }
    return countQuery.compilation(observation);
  }

  private Slice<R> resumeSlice(
      SliceContinuation token, int pageSize, @Nullable OperationObservation observation) {
    DefaultSliceContinuation continuation = QueryModels.continuation(token);
    List<@Nullable Object> keysetValues =
        continuation.mode() == DefaultSliceContinuation.Mode.KEYSET
            ? continuation.keysetValues()
            : List.of();
    validateContinuation(continuation, keysetValues, observation);
    return switch (continuation.mode()) {
      case OFFSET -> fetchOffsetSlice(continuation.nextOffset(), pageSize, observation);
      case KEYSET -> fetchKeysetSlice(keysetValues, pageSize, observation);
    };
  }

  private Slice<R> fetchOffsetSlice(
      long offset, int pageSize, @Nullable OperationObservation observation) {
    int limit = sizePlusOne(pageSize);
    QueryCompilation<R> query =
        compilation(
            new QueryPagination.Offset(limit, offset), observation, StatementPurpose.PRIMARY);
    List<R> rows =
        operations.fetchSliceList(
            query.plan(),
            query.argument(),
            executionContext,
            pageSize,
            observation,
            StatementPurpose.PRIMARY);
    boolean hasNext = rows.size() > pageSize;
    List<R> items = visibleRows(rows, pageSize);
    requireAllowedNulls(items, "non-null query produced a null slice item");
    SliceContinuation continuation =
        hasNext
            ? DefaultSliceContinuation.offset(
                queryFingerprint(observation),
                orderSignature(),
                nextOffset(offset, pageSize),
                parameterDigest())
            : null;
    return Slice.of(items, pageSize, continuation);
  }

  private Slice<R> fetchKeysetSlice(
      @Nullable List<@Nullable Object> anchors,
      int pageSize,
      @Nullable OperationObservation observation) {
    validatePaginationOrder(true);
    QueryPagination pagination =
        anchors == null
            ? new QueryPagination.LimitOnly(sizePlusOne(pageSize))
            : new QueryPagination.Keyset(sizePlusOne(pageSize), anchors);
    QueryCompilation<OrderedRow<R>> query = orderedCompilation(pagination, observation);
    List<OrderedRow<R>> rows =
        requireNonNullValues(
            operations.fetchSliceList(
                query.plan(),
                query.argument(),
                executionContext,
                pageSize,
                observation,
                StatementPurpose.PRIMARY));
    boolean hasNext = rows.size() > pageSize;
    List<R> items = new ArrayList<>(Math.min(rows.size(), pageSize));
    for (int index = 0; index < Math.min(rows.size(), pageSize); index++) {
      items.add(rows.get(index).value());
    }
    requireAllowedNulls(items, "non-null query produced a null slice item");
    SliceContinuation next = null;
    if (hasNext) {
      OrderedRow<R> anchor = rows.get(pageSize - 1);
      List<Boolean> nullMarkers = anchor.orderValues().stream().map(Objects::isNull).toList();
      next =
          DefaultSliceContinuation.keyset(
              queryFingerprint(observation),
              orderSignature(),
              state.orderBy().stream().map(item -> QueryModels.selectable(item).sqlType()).toList(),
              nullMarkers,
              anchor.orderValues(),
              parameterDigest());
    }
    return Slice.of(items, pageSize, next);
  }

  private void validatePaginationOrder(boolean keyset) {
    if (state.orderBy().isEmpty()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_ORDER_REQUIRED,
          "pagination requires explicit stable ORDER BY");
    }
    CompiledQueryStructure structure = analysis().structure();
    validateOrderScope(structure.fromClause());
    if (state.distinct()) {
      if (!hasStableDistinctOrdering()) {
        SqlExpression<?> missing =
            state.selected().expressionIdentities().stream()
                .filter(
                    expression ->
                        state.orderBy().stream()
                            .noneMatch(
                                item ->
                                    SelectableSupport.expressionIdentity(
                                            QueryModels.selectable(item))
                                        .equals(expression)))
                .map(SelectableSupport.ExpressionIdentity::expression)
                .findFirst()
                .orElseThrow();
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
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
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
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
    List<SelectableSupport.ExpressionIdentity> expressions =
        state.selected().expressionIdentities();
    return expressions.stream()
        .allMatch(
            expression ->
                state.orderBy().stream()
                    .anyMatch(
                        item ->
                            SelectableSupport.expressionIdentity(QueryModels.selectable(item))
                                .equals(expression)));
  }

  private void validateOrderScope(FromClause fromClause) {
    for (SortSpecification item : state.orderBy()) {
      SqlExpression<?> expression = QueryModels.sort(item).expression();
      if (expression instanceof ColumnExpression<?, ?> column
          && fromClause.occurrenceOf(column.table()).isEmpty()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_SOURCE_OUT_OF_SCOPE,
            "ORDER BY expression '"
                + expressionSummary(expression)
                + "' is not visible in the final FROM/JOIN scope");
      }
      if (expression instanceof DerivedColumnExpression<?> column
          && fromClause.occurrenceOf(column.relation()).isEmpty()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_SOURCE_OUT_OF_SCOPE,
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
                      QueryValidationException.of(
                          SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
                          "pagination ORDER BY requires primary-key metadata for "
                              + occurrenceDescription(occurrence)));
      for (PropertyMeta<?, ?> property : primaryKey.properties()) {
        boolean present =
            state.orderBy().stream()
                .anyMatch(
                    item -> {
                      Selectable<?> selectable = QueryModels.selectable(item);
                      return selectable instanceof QueryColumn<?, ?> column
                          && QueryTableModel.of(column.table()).tableExpression() == occurrenceTable
                          && column.property() == property;
                    });
        if (!present) {
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
              "pagination ORDER BY is not stable for "
                  + occurrenceDescription(occurrence)
                  + "; missing primary-key property '"
                  + property.name()
                  + "'");
        }
      }
    }
  }

  private void validateContinuation(
      DefaultSliceContinuation continuation,
      List<@Nullable Object> keysetValues,
      @Nullable OperationObservation observation) {
    if (continuation.formatVersion() != DefaultSliceContinuation.FORMAT_VERSION) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_CONTINUATION_INVALID, "unsupported continuation format version");
    }
    if (continuation.generatedAbi() != GeneratedModelAbi.CURRENT) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_CONTINUATION_INVALID,
          "continuation generated-model ABI is incompatible");
    }
    if (!continuation.queryFingerprint().equals(queryFingerprint(observation))
        || !continuation.orderSignature().equals(orderSignature())
        || !continuation.parameterDigest().equals(parameterDigest())) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_CONTINUATION_INVALID,
          "continuation does not belong to this query structure, ordering, or parameter set");
    }
    if (continuation.mode() == DefaultSliceContinuation.Mode.KEYSET) {
      if (keysetValues.size() != state.orderBy().size()
          || continuation.sqlTypes().size() != state.orderBy().size()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_CONTINUATION_VALUE,
            "continuation ordering value count does not match");
      }
      for (int index = 0; index < state.orderBy().size(); index++) {
        SortSpecification sort = state.orderBy().get(index);
        Object value = keysetValues.get(index);
        if (continuation.sqlTypes().get(index) != QueryModels.selectable(sort).sqlType()
            || continuation.nullMarkers().get(index) != (value == null)
            || (value != null && !QueryModels.selectable(sort).javaType().isInstance(value))) {
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_CONTINUATION_VALUE,
              "continuation value type does not match ordering expression '"
                  + SelectableSupport.summary(QueryModels.selectable(sort))
                  + "'");
        }
      }
    }
  }

  String queryFingerprint() {
    return queryFingerprint(null);
  }

  private String queryFingerprint(@Nullable OperationObservation observation) {
    QueryCompilation<R> structure =
        compilation(QueryPagination.None.INSTANCE, observation, StatementPurpose.PRIMARY);
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
              .occurrenceOf(QueryTableModel.of(column.table()).tableExpression())
              .orElseThrow(
                  () ->
                      QueryValidationException.of(
                          SkisErrorCode.QUERY_SOURCE_OUT_OF_SCOPE,
                          "ORDER BY expression '"
                              + expressionSummary(QueryModels.sort(item).expression())
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
          .append(QueryModels.selectable(item).javaType().getName())
          .append(':')
          .append(QueryModels.selectable(item).sqlType())
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
                QueryValidationException.of(
                    SkisErrorCode.QUERY_METADATA_REQUIRED,
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
    if (expression instanceof DerivedColumnExpression<?> column) {
      return column.relation().alias().value() + '.' + column.output().name().value();
    }
    return expression.getClass().getSimpleName();
  }

  private String parameterDigest() {
    MessageDigest digest = sha256();
    List<@Nullable Object> arguments = conditionArguments();
    for (Object argument : arguments) {
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

  private DefaultSelectQuery<R> chain(QueryCondition newPredicate, boolean conjunction) {
    Objects.requireNonNull(newPredicate, "predicate");
    QueryCondition structure = QueryConditions.structure(newPredicate);
    return copy(
        state.chainWhere(structure, conjunction),
        parameters.merge(QueryConditions.parameters(newPredicate)),
        executionContext);
  }

  private DefaultSelectQuery<R> copy(
      SelectQueryState<R> replacement,
      QueryParameters replacementParameters,
      ExecutionContext context) {
    return replacement == state
            && replacementParameters == parameters
            && context == executionContext
        ? this
        : new DefaultSelectQuery<>(
            operations, compiler, fastPlans, replacement, replacementParameters, context);
  }

  private JoinOnStep<SelectQuery<R>> joinOn(JoinType type, QueryRelation relation) {
    return new DefaultJoinOnStep<>(this, type, Objects.requireNonNull(relation, "relation"));
  }

  DefaultSelectQuery<R> appendJoin(
      JoinType type, QueryRelation joinedRelation, @Nullable QueryCondition on) {
    QueryCondition structure = on == null ? null : QueryConditions.structure(on);
    QueryParameters appendedParameters =
        on == null ? parameters : parameters.merge(QueryConditions.parameters(on));
    return copy(
        state.appendJoin(type, joinedRelation, structure), appendedParameters, executionContext);
  }

  private void requireDeclaredParameter(QueryParameter<?> parameter) {
    for (QueryParameter<?> declared :
        state.structure().validationStructure().parameterReferences()) {
      if (declared == parameter) {
        return;
      }
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_PARAMETER_DECLARATION,
        "binding is not declared by the complete query description: " + parameter);
  }

  private SingleRow<R> singleRow(JdbcRow<R> row) {
    if (!row.present()) {
      return SingleRow.noRow();
    }
    //noinspection RedundantTypeArguments
    return DefaultSingleRow.<R>decoded(row.value(), state.selected().nullableResult());
  }

  private void requireAllowedNulls(List<? extends R> values, String message) {
    if (state.selected().nullableResult()) {
      return;
    }
    for (R value : values) {
      if (value == null) {
        throw QueryValidationException.of(SkisErrorCode.QUERY_NON_NULL_RESULT, message);
      }
    }
  }

  private QueryArguments conditionArguments() {
    return analysis().arguments();
  }

  private Object fastArgument() {
    return analysis().arguments().planArgument();
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
        QueryArguments arguments = structure.arguments(parameters);
        existing = new QueryAnalysis(structure, arguments);
        analysis = existing;
      }
      return existing;
    }
  }

  private QueryAnalysis countAnalysis() {
    QueryAnalysis existing = countAnalysis;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      existing = countAnalysis;
      if (existing == null) {
        QueryAnalysis contentAnalysis = analysis();
        CompiledQueryStructure structure = state.countStructure();
        List<QueryParameter<?>> references = structure.parameterReferences();
        QueryArguments arguments =
            references.equals(contentAnalysis.structure().parameterReferences())
                ? contentAnalysis.arguments()
                : structure.projectedArguments(parameters);
        existing = new QueryAnalysis(structure, arguments);
        countAnalysis = existing;
      }
      return existing;
    }
  }

  private static QueryColumn<?, ?> requirePhysicalPaginationColumn(SortSpecification item) {
    if (QueryModels.selectable(item) instanceof QueryColumn<?, ?> column) {
      return column;
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        "pagination identity analysis does not yet support ORDER BY expression '"
            + SelectableSupport.summary(QueryModels.selectable(item))
            + "'");
  }

  private static int sizePlusOne(int pageSize) {
    try {
      return Math.addExact(pageSize, 1);
    } catch (ArithmeticException exception) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_OVERFLOW, "pageSize + 1 overflows int", exception);
    }
  }

  private static long nextOffset(long offset, int pageSize) {
    try {
      return Math.addExact(offset, pageSize);
    } catch (ArithmeticException exception) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_OVERFLOW,
          "slice continuation offset overflows long",
          exception);
    }
  }

  private static <T extends @Nullable Object> List<T> visibleRows(List<T> rows, int pageSize) {
    List<T> items = new ArrayList<>(Math.min(rows.size(), pageSize));
    items.addAll(rows.subList(0, Math.min(rows.size(), pageSize)));
    return items;
  }

  private static <T extends @Nullable Object> List<T> requireNonNullValues(List<T> values) {
    for (T value : values) {
      if (value == null) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_NON_NULL_RESULT, "ordered query produced a null row");
      }
    }
    return values;
  }

  private record QueryAnalysis(CompiledQueryStructure structure, QueryArguments arguments) {

    private QueryAnalysis {
      Objects.requireNonNull(structure, "structure");
      Objects.requireNonNull(arguments, "arguments");
    }
  }

  private record CachedPlan<T extends @Nullable Object>(
      CompiledQueryPlan<T, Object> plan, StatementAst ast) {

    private CachedPlan {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(ast, "ast");
    }
  }

  private static final class LocalPlanCache<T extends @Nullable Object> {

    private volatile @Nullable RecentPlan<T> recent;

    private QueryCompilation<T> getOrCompile(
        QueryPaginationShape pagination, Supplier<QueryCompilation<T>> compiler, Object argument) {
      RecentPlan<T> existing = recent;
      if (existing != null && existing.pagination().equals(pagination)) {
        return new QueryCompilation<>(existing.plan().plan(), argument, existing.plan().ast());
      }
      QueryCompilation<T> compiled = Objects.requireNonNull(compiler.get(), "compiled query");
      recent = new RecentPlan<>(pagination, new CachedPlan<>(compiled.plan(), compiled.ast()));
      return compiled;
    }

    private record RecentPlan<T extends @Nullable Object>(
        QueryPaginationShape pagination, CachedPlan<T> plan) {}
  }
}
