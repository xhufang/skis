package io.skis.query.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.internal.JdbcPageResult;
import io.skis.jdbc.internal.JdbcRow;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.query.CountQuery;
import io.skis.query.ExecutableSelectQuery;
import io.skis.query.NullPlacement;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryCursor;
import io.skis.query.QueryTable;
import io.skis.query.SingleRow;
import io.skis.query.Slice;
import io.skis.query.SliceContinuation;
import io.skis.query.SliceRequest;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Built-in unified immutable query implementation. */
class DefaultExecutableSelectQuery<R extends @Nullable Object>
    extends AbstractSelectQuery<R, ExecutableSelectQuery<R>> implements ExecutableSelectQuery<R> {

  final DefaultQueryOperations operations;
  final QueryPlanCompiler compiler;
  final @Nullable EntityPlanSet<?> fastPlans;
  final ExecutionContext executionContext;
  // Shared by option-only variants and by repeated executions of one model; never by other state.
  final CompiledState<R> caches;

  DefaultExecutableSelectQuery(
      DefaultQueryOperations operations,
      QueryPlanCompiler compiler,
      @Nullable EntityPlanSet<?> fastPlans,
      SelectQueryState<R> state,
      QueryParameters parameters,
      ExecutionContext executionContext) {
    this(operations, compiler, fastPlans, state, parameters, executionContext, Set.of());
  }

  DefaultExecutableSelectQuery(
      DefaultQueryOperations operations,
      QueryPlanCompiler compiler,
      @Nullable EntityPlanSet<?> fastPlans,
      SelectQueryState<R> state,
      QueryParameters parameters,
      ExecutionContext executionContext,
      Set<QueryParameter<?>> orderingOnlyParameters) {
    this(
        operations,
        compiler,
        fastPlans,
        state,
        parameters,
        executionContext,
        orderingOnlyParameters,
        null);
  }

  DefaultExecutableSelectQuery(
      DefaultQueryOperations operations,
      QueryPlanCompiler compiler,
      @Nullable EntityPlanSet<?> fastPlans,
      SelectQueryState<R> state,
      QueryParameters parameters,
      ExecutionContext executionContext,
      Set<QueryParameter<?>> orderingOnlyParameters,
      @Nullable CompiledState<R> caches) {
    super(state, parameters, orderingOnlyParameters);
    this.operations = Objects.requireNonNull(operations, "operations");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    this.fastPlans = fastPlans;
    this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
    if (caches != null && !caches.belongsTo(compiler)) {
      throw new IllegalArgumentException("compiled query caches belong to another compiler");
    }
    this.caches = caches == null ? new CompiledState<>(compiler) : caches;
  }

  @Override
  public DefaultExecutableSelectQuery<R> withOptions(ExecutionOptions options) {
    Objects.requireNonNull(options, "options");
    return executionContext.executionOptions().equals(options)
        ? this
        : new DefaultExecutableSelectQuery<>(
            operations,
            compiler,
            fastPlans,
            state,
            parameters,
            ExecutionContext.of(options),
            orderingOnlyParameters,
            caches);
  }

  @Override
  DefaultExecutableSelectQuery<R> recreate(
      SelectQueryState<R> replacement, QueryParameters values, Set<QueryParameter<?>> ordering) {
    return new DefaultExecutableSelectQuery<>(
        operations, compiler, fastPlans, replacement, values, executionContext, ordering);
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
                observation);
      } else {
        QueryCompilation<R> query =
            compilation(QueryPagination.None.INSTANCE, observation, StatementPurpose.PRIMARY);
        row =
            operations.fetchNullableOne(
                query.plan(), query.argument(), executionContext, observation);
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
                  query.plan(), query.argument(), executionContext, observation));
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
                observation);
      } else {
        QueryCompilation<R> query =
            compilation(QueryPagination.None.INSTANCE, observation, StatementPurpose.PRIMARY);
        values =
            operations.fetchList(query.plan(), query.argument(), executionContext, observation);
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
      boolean keyset =
          sliceRequest.mode() == DefaultSliceRequest.Mode.KEYSET_FIRST
              || (sliceRequest.mode() == DefaultSliceRequest.Mode.RESUME
                  && QueryModels.continuation(sliceRequest.continuation()).mode()
                      == DefaultSliceContinuation.Mode.KEYSET);
      validatePaginationOrder(keyset);
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
          observation);
    } catch (RuntimeException | Error failure) {
      operations.completeFailed(observation, failure);
      throw failure;
    }
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
    return caches.plansByPagination.getOrCompile(
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
                purpose,
                this::sourceContext),
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
    return caches.orderedPlansByPagination.getOrCompile(
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
                StatementPurpose.PRIMARY,
                this::sourceContext),
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
    CachedPlan<R> existing = caches.fastPlan.get();
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
    CachedPlan<R> published = caches.fastPlan.compareAndExchange(null, compiled);
    return published == null ? compiled : published;
  }

  QueryCompilation<Long> countCompilation(@Nullable OperationObservation observation) {
    QueryAnalysis queryAnalysis = countAnalysis();
    CompiledQueryStructure countStructure = queryAnalysis.structure();
    QueryArguments countArguments = queryAnalysis.arguments();
    Object countArgument = countArguments.planArgument();
    CachedPlan<Long> existing = caches.countPlan.get();
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
            StatementPurpose.PAGE_COUNT,
            this::sourceContext);
    CachedPlan<Long> cached = new CachedPlan<>(compiled.plan(), compiled.ast());
    CachedPlan<Long> published = caches.countPlan.compareAndExchange(null, cached);
    if (published == null) {
      return compiled;
    }
    return new QueryCompilation<>(published.plan(), countArgument, published.ast());
  }

  private QueryCompilation<Long> requireExplicitCount(
      CountQuery explicitCountQuery, @Nullable OperationObservation observation) {
    DefaultCountQuery countQuery = requireBuiltInCount(explicitCountQuery);
    if (countQuery.operations() != null && countQuery.operations() != operations) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_COUNT_CONTRACT,
          "explicit count must use the same execution and data-source scope as the content query");
    }
    if (countQuery.operations() != null
        && !countQuery
            .executionContext()
            .executionOptions()
            .equals(executionContext.executionOptions())) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_COUNT_CONTRACT,
          "explicit count must use the same execution options as the content query");
    }
    return countQuery.compilation(operations, executionContext, observation);
  }

  /** Rejects foreign count plans; shared by executable terminals and request construction. */
  static DefaultCountQuery requireBuiltInCount(CountQuery explicitCountQuery) {
    if (Objects.requireNonNull(explicitCountQuery, "explicitCountQuery")
        instanceof DefaultCountQuery countQuery) {
      return countQuery;
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_COUNT_CONTRACT,
        "explicit count must be a built-in SKIS CountQuery created with countQuery()");
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
            query.plan(), query.argument(), executionContext, pageSize, observation);
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
                query.plan(), query.argument(), executionContext, pageSize, observation));
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
    if (keyset) {
      // Keep unsupported seek expressions ahead of identity or entity metadata diagnostics.
      for (SortSpecification item : state.orderBy()) {
        requirePhysicalPaginationColumn(item);
      }
    }
    CompiledQueryStructure structure = analysis().structure();
    validateOrderScope(structure.fromClause());
    ResolvedStatement context = paginationContext();
    if (keyset) {
      if (context.isGrouped()
          || structure.fromClause().occurrences().stream()
              .anyMatch(occurrence -> occurrence.source().entityTable().isEmpty())) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
            "grouped and derived keyset pagination is not supported");
      }
      if (state.orderBy().isEmpty()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PAGINATION_ORDER_REQUIRED,
            "keyset pagination requires explicit stable ORDER BY");
      }
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
    if (!caches.offsetOrderingValidated) {
      if (state.distinct() && !context.atMostOneRow()) {
        validateDistinctPaginationOrder(context);
      } else {
        context.validateOffsetOrdering();
      }
      caches.offsetOrderingValidated = true;
    }
  }

  private void validateDistinctPaginationOrder(ResolvedStatement context) {
    if (state.orderBy().isEmpty()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_ORDER_REQUIRED,
          "pagination requires explicit stable ORDER BY");
    }
    // The complete original occurrence has been validated by paginationContext(). The DSL
    // still owns the logical parameter references that separate nested occurrence layouts erase.
    Set<SelectableSupport.ExpressionIdentity> ordered = new HashSet<>();
    for (SortSpecification order : state.orderBy()) {
      ordered.add(SelectableSupport.expressionIdentity(QueryModels.selectable(order)));
    }
    List<SelectableSupport.ExpressionIdentity> outputs = state.selected().expressionIdentities();
    for (SelectableSupport.ExpressionIdentity output : outputs) {
      if (!ordered.contains(output)) {
        // Keep the exact DSL match fast path, including its logical parameter identities.
        // Only a missing match needs the resolved equivalence of raw derived outputs.
        List<Integer> representatives = context.distinctOutputRepresentatives();
        boolean[] covered = new boolean[outputs.size()];
        for (int index = 0; index < outputs.size(); index++) {
          if (ordered.contains(outputs.get(index))) {
            covered[representatives.get(index)] = true;
          }
        }
        for (int representative : representatives) {
          if (!covered[representative]) {
            throw QueryValidationException.of(
                SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
                "distinct pagination ORDER BY must cover every selected expression; missing '"
                    + expressionSummary(outputs.get(representative).expression())
                    + "'");
          }
        }
        return;
      }
    }
  }

  private ResolvedStatement sourceContext() {
    CompiledState<R> shared = caches;
    ResolvedStatement existing = shared.sourceContext;
    if (existing != null) {
      return existing;
    }
    synchronized (shared) {
      existing = shared.sourceContext;
      if (existing == null) {
        existing =
            compiler.resolveSource(state.selected(), analysis().structure(), state.distinct());
        shared.sourceContext = existing;
      }
      return existing;
    }
  }

  private ResolvedStatement paginationContext() {
    CompiledState<R> shared = caches;
    ResolvedStatement existing = shared.paginationContext;
    if (existing != null) {
      return existing;
    }
    synchronized (shared) {
      existing = shared.paginationContext;
      if (existing == null) {
        existing =
            compiler.resolvePagination(
                state.selected(), analysis().structure(), state.distinct(), this::sourceContext);
        shared.paginationContext = existing;
      }
      return existing;
    }
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

  private String queryFingerprint(@Nullable OperationObservation observation) {
    QueryCompilation<R> structure =
        compilation(QueryPagination.None.INSTANCE, observation, StatementPurpose.PRIMARY);
    FromClause fromClause = analysis().structure().fromClause();
    MessageDigest digest = sha256();
    updateDigest(digest, "query-fingerprint-v2");
    updateDigest(digest, structure.plan().dialectId());
    updateDigest(digest, structure.plan().sql());
    updateDigest(digest, state.selected().structuralIdentity());
    updateDigest(digest, paginationContext().structureKey().orElseThrow().canonicalForm());
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
    return paginationContext().orderingSignature();
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
    CompiledState<R> shared = caches;
    QueryAnalysis existing = shared.analysis;
    if (existing != null) {
      return existing;
    }
    synchronized (shared) {
      existing = shared.analysis;
      if (existing == null) {
        CompiledQueryStructure structure = state.structure();
        QueryArguments arguments = structure.arguments(parameters);
        existing = new QueryAnalysis(structure, arguments);
        shared.analysis = existing;
      }
      return existing;
    }
  }

  private QueryAnalysis countAnalysis() {
    CompiledState<R> shared = caches;
    QueryAnalysis existing = shared.countAnalysis;
    if (existing != null) {
      return existing;
    }
    synchronized (shared) {
      existing = shared.countAnalysis;
      if (existing == null) {
        QueryAnalysis contentAnalysis = analysis();
        CompiledQueryStructure structure = state.countStructure();
        List<QueryParameter<?>> references = structure.parameterReferences();
        QueryArguments arguments =
            references.equals(contentAnalysis.structure().parameterReferences())
                ? contentAnalysis.arguments()
                : structure.projectedArguments(parameters);
        existing = new QueryAnalysis(structure, arguments);
        shared.countAnalysis = existing;
      }
      return existing;
    }
  }

  /**
   * Option-independent analyses and plans of one immutable state/parameter pair for one compiler.
   *
   * <p>Execution options only affect JDBC execution, never SQL, plans, or validation, so option
   * variants and repeated model requests share one instance instead of recompiling per execution.
   */
  static final class CompiledState<R extends @Nullable Object> {

    private final QueryPlanCompiler compiler;
    private volatile @Nullable QueryAnalysis analysis;
    private volatile @Nullable QueryAnalysis countAnalysis;
    private final AtomicReference<@Nullable CachedPlan<R>> fastPlan = new AtomicReference<>();
    private final LocalPlanCache<R> plansByPagination = new LocalPlanCache<>();
    private final LocalPlanCache<OrderedRow<R>> orderedPlansByPagination = new LocalPlanCache<>();
    private final AtomicReference<@Nullable CachedPlan<Long>> countPlan = new AtomicReference<>();
    // Pagination retains the same query-owned source stage after its additional checks complete.
    private volatile @Nullable ResolvedStatement sourceContext;
    private volatile @Nullable ResolvedStatement paginationContext;
    private volatile boolean offsetOrderingValidated;

    CompiledState(QueryPlanCompiler compiler) {
      this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    boolean belongsTo(QueryPlanCompiler candidate) {
      return compiler == candidate;
    }
  }

  private static QueryColumn<?, ?> requirePhysicalPaginationColumn(SortSpecification item) {
    if (QueryModels.selectable(item) instanceof QueryColumn<?, ?> column) {
      return column;
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        "keyset pagination requires physical-column ORDER BY; unsupported expression '"
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
