package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.support.SqlCompiler;
import io.skis.dialect.support.SqlTemplate;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.mapping.spi.RowDecoder;
import io.skis.metadata.PropertyMeta;
import io.skis.query.NullPlacement;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.query.internal.QueryPlanResolver.CompilationObservation;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.Limit;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectPagination;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Compiles immutable query shapes into value-independent JDBC plans. */
final class QueryPlanCompiler {

  private final Dialect dialect;
  private final EntityRuntimeRegistry runtimeRegistry;
  private final QueryPlanResolver plans;

  QueryPlanCompiler(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, QueryPlanResolver plans) {
    this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.plans = Objects.requireNonNull(plans, "plans");
  }

  String dialectId() {
    return dialect.id();
  }

  /** Compiles the no-predicate or one-property equality Fast Path. */
  <E> CompiledQueryPlan<E, Object> compile(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable PropertyMeta<E, ?> equalityProperty) {
    requireCanonicalModel(model, table);
    PredicateShape<E> shape = equalityShape(table, equalityProperty);
    SelectStatement statement =
        constructedStatement(
            () ->
                new SelectStatement(
                    QueryTableModel.of(table).selections(),
                    QueryTableModel.of(table).tableExpression(),
                    shape.ast()));
    return compilePlanFromProperties(model, statement, shape.properties(), model.fullRowDecoder());
  }

  <E> CompiledQueryPlan<E, Object> compileQuery(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      CompiledQueryStructure structure,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    requireCanonicalModel(model, table);
    Objects.requireNonNull(structure, "structure");
    return compileSelection(
            SelectedResult.entity(table),
            structure,
            List.of(),
            false,
            QueryPagination.None.INSTANCE,
            List.of(),
            QueryArguments.placeholders(structure.parameterSlots().size()),
            observation,
            purpose)
        .plan();
  }

  <R extends @Nullable Object> QueryCompilation<Long> compileCount(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      boolean distinct,
      QueryArguments statementArguments,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    CompilationObservation compilation =
        observation == null ? null : new CompilationObservation(observation, purpose);
    try {
      return prepareCount(selected, structure, distinct, statementArguments, compilation);
    } catch (RuntimeException | Error failure) {
      if (compilation != null) {
        compilation.preparationFailed(failure);
      }
      throw failure;
    }
  }

  private <R extends @Nullable Object> QueryCompilation<Long> prepareCount(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      boolean distinct,
      QueryArguments statementArguments,
      @Nullable CompilationObservation observation) {
    Objects.requireNonNull(structure, "structure");
    TableRuntimeScope sourceScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.validationStructure().fromClause());
    ResolvedStatement sourceContext =
        validateSelectionSource(selected, structure, distinct, sourceScope);
    TableRuntimeScope runtimeScope = sourceScope.forFromClause(structure.fromClause());
    SqlExpression<?> distinctExpression = null;
    if (distinct) {
      List<SqlExpression<?>> compiledSelections = selectionExpressions(structure, selected);
      ResolvedResultShape<R> selection = selected.resolve(runtimeScope, compiledSelections);
      distinctExpression =
          selected.automaticDistinctCountExpression(
              !structure.fromClause().joins().isEmpty(), selection.expressions());
    }
    return compileResolvedCount(
        selected,
        sourceContext,
        structure,
        runtimeScope,
        statementArguments,
        distinctExpression,
        observation);
  }

  private QueryCompilation<Long> compileResolvedCount(
      SelectedResult<?> selected,
      ResolvedStatement sourceContext,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      QueryArguments statementArguments,
      @Nullable SqlExpression<?> distinctExpression,
      @Nullable CompilationObservation observation) {
    if (!structure.groupBy().isEmpty() || structure.having() != null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_COUNT_CONTRACT,
          "countQuery does not yet support GROUP BY or HAVING descriptions");
    }
    CountAst count =
        constructedStatement(
            () -> new CountAst(structure.fromClause(), structure.where(), distinctExpression));
    InputsBuilder inputs = new InputsBuilder(runtimeScope, structure);
    List<LogicalParameter> parameters = inputs.logicalParameters();
    validateLogicalParameters(parameters);
    validateStatementArguments(
        statementArguments,
        structure.parameterSlots().size(),
        parameters.size(),
        QueryPaginationShape.none());
    ResolvedStatement compilationContext = prepareContext(count);
    return plans.resolve(
        sourceContext,
        compilationContext.statement(),
        selected,
        QueryPlanKey.PlanVariant.count(),
        parameters,
        inputs.parameterSources,
        statementArguments.planArgument(),
        () ->
            renderPlan(
                compilationContext,
                parameters,
                (resultSet, context) -> {
                  long value = resultSet.getLong(1);
                  if (resultSet.wasNull()) {
                    throw QueryValidationException.of(
                        SkisErrorCode.QUERY_RESULT_MAPPING, "COUNT result was unexpectedly null");
                  }
                  return value;
                }),
        observation);
  }

  <R extends @Nullable Object> QueryCompilation<OrderedRow<R>> compileOrdered(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      QueryArguments statementArguments,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    CompilationObservation compilation =
        observation == null ? null : new CompilationObservation(observation, purpose);
    try {
      return prepareOrdered(
          selected, structure, orderBy, distinct, pagination, statementArguments, compilation);
    } catch (RuntimeException | Error failure) {
      if (compilation != null) {
        compilation.preparationFailed(failure);
      }
      throw failure;
    }
  }

  private <R extends @Nullable Object> QueryCompilation<OrderedRow<R>> prepareOrdered(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      QueryArguments statementArguments,
      @Nullable CompilationObservation observation) {
    Objects.requireNonNull(structure, "structure");
    TableRuntimeScope runtimeScope;
    if (structure.validationSource() != null) {
      TableRuntimeScope sourceScope =
          TableRuntimeScope.resolve(runtimeRegistry, structure.validationStructure().fromClause());
      validateSelectionSource(selected, structure, distinct, sourceScope);
      runtimeScope = sourceScope.forFromClause(structure.fromClause());
    } else {
      runtimeScope = TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    }
    ResolvedResultShape<R> selection =
        resolveSelection(selected, structure, distinct, runtimeScope);
    return compileResolvedOrdered(
        selected,
        structure,
        runtimeScope,
        selection,
        orderBy,
        distinct,
        pagination,
        statementArguments,
        observation);
  }

  private <R extends @Nullable Object> QueryCompilation<OrderedRow<R>> compileResolvedOrdered(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      ResolvedResultShape<R> selection,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      QueryArguments statementArguments,
      @Nullable CompilationObservation observation) {
    List<OrderByItem> compiledOrder = orderItems(structure, orderBy);
    List<HiddenSelection> hidden = new ArrayList<>();
    int[] indexes = new int[orderBy.size()];
    for (int index = 0; index < orderBy.size(); index++) {
      SqlExpression<?> expression = compiledOrder.get(index).expression();
      int visibleIndex = selection.expressions().indexOf(expression);
      if (visibleIndex >= 0) {
        indexes[index] = visibleIndex + 1;
      } else {
        if (distinct) {
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_ORDER_BY_NOT_SELECTED,
              "distinct keyset ordering must select every ordering expression");
        }
        indexes[index] = selection.expressions().size() + hidden.size() + 1;
        hidden.add(new HiddenSelection(expression, Identifier.of("__skis_order_" + index)));
      }
    }
    List<ResolvedValueMapping<?>> resolvedOrderMappings = new ArrayList<>(orderBy.size());
    for (int index = 0; index < orderBy.size(); index++) {
      resolvedOrderMappings.add(
          resolveValueMapping(
              QueryModels.selectable(orderBy.get(index)),
              compiledOrder.get(index).expression(),
              runtimeScope));
    }
    List<ProjectionMapping.ValueReader<?>> mutableOrderReaders = new ArrayList<>(orderBy.size());
    for (int index = 0; index < resolvedOrderMappings.size(); index++) {
      mutableOrderReaders.add(resolvedOrderMappings.get(index).reader(indexes[index], false));
    }
    RowDecoder<OrderedRow<R>> decoder = orderedRowDecoder(selection.decoder(), mutableOrderReaders);
    return compileResolvedSelection(
        selected,
        QueryPlanKey.PlanVariant.orderedContent(QueryPaginationShape.from(pagination)),
        structure,
        runtimeScope,
        new ResolvedResultShape<>(selection.expressions(), decoder, selection.selections()),
        orderBy,
        distinct,
        pagination,
        hidden,
        statementArguments,
        observation);
  }

  private static <R extends @Nullable Object> RowDecoder<OrderedRow<R>> orderedRowDecoder(
      RowDecoder<R> valueDecoder, List<ProjectionMapping.ValueReader<?>> mutableOrderReaders) {
    List<ProjectionMapping.ValueReader<?>> orderReaders = List.copyOf(mutableOrderReaders);
    int orderValueCount = orderReaders.size();
    return (resultSet, context) -> {
      R value = valueDecoder.decode(resultSet, context);
      List<@Nullable Object> orderValues = new ArrayList<>(orderValueCount);
      for (ProjectionMapping.ValueReader<?> reader : orderReaders) {
        orderValues.add(reader.read(resultSet, context));
      }
      return new OrderedRow<>(value, orderValues);
    };
  }

  <R extends @Nullable Object> QueryCompilation<R> compileSelection(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden,
      QueryArguments statementArguments,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    CompilationObservation compilation =
        observation == null ? null : new CompilationObservation(observation, purpose);
    try {
      return prepareSelection(
          selected,
          structure,
          orderBy,
          distinct,
          pagination,
          hidden,
          statementArguments,
          compilation);
    } catch (RuntimeException | Error failure) {
      if (compilation != null) {
        compilation.preparationFailed(failure);
      }
      throw failure;
    }
  }

  private <R extends @Nullable Object> QueryCompilation<R> prepareSelection(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden,
      QueryArguments statementArguments,
      @Nullable CompilationObservation observation) {
    Objects.requireNonNull(structure, "structure");
    TableRuntimeScope runtimeScope;
    if (structure.validationSource() != null) {
      TableRuntimeScope sourceScope =
          TableRuntimeScope.resolve(runtimeRegistry, structure.validationStructure().fromClause());
      validateSelectionSource(selected, structure, distinct, sourceScope);
      runtimeScope = sourceScope.forFromClause(structure.fromClause());
    } else {
      runtimeScope = TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    }
    ResolvedResultShape<R> selection =
        resolveSelection(selected, structure, distinct, runtimeScope);
    return compileResolvedSelection(
        selected,
        QueryPlanKey.PlanVariant.content(QueryPaginationShape.from(pagination)),
        structure,
        runtimeScope,
        selection,
        orderBy,
        distinct,
        pagination,
        hidden,
        statementArguments,
        observation);
  }

  private <R extends @Nullable Object> ResolvedResultShape<R> resolveSelection(
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      boolean distinct,
      TableRuntimeScope runtimeScope) {
    try {
      return selected.resolve(runtimeScope, selectionExpressions(structure, selected));
    } catch (QueryValidationException failure) {
      if (failure.code() == SkisErrorCode.QUERY_TYPE_MISMATCH) {
        // Resolving a scalar/derived output can reach an inner aggregate before its occurrence
        // is analyzed. Recover that location only on failure, as with bound aggregate inputs.
        validateSelectionSource(selected, structure, distinct, runtimeScope);
      }
      throw failure;
    }
  }

  private <R extends @Nullable Object> QueryCompilation<R> compileResolvedSelection(
      SelectedResult<?> selected,
      QueryPlanKey.PlanVariant variant,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      ResolvedResultShape<R> selection,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden,
      QueryArguments statementArguments,
      @Nullable CompilationObservation observation) {
    List<OrderByItem> orderAst = orderItems(structure, orderBy);
    InputsBuilder inputs;
    try {
      inputs = new InputsBuilder(runtimeScope, structure);
    } catch (QueryValidationException failure) {
      if (failure.code() == SkisErrorCode.QUERY_TYPE_MISMATCH) {
        // A bound aggregate can fail before the final statement is analyzed. Only on failure,
        // validate its complete original occurrence so the error retains its clause/block.
        validateSelectionSource(selected, structure, distinct, runtimeScope);
      }
      throw failure;
    }
    SelectPagination paginationAst = inputs.pagination(orderBy, orderAst, pagination);
    SelectStatement statement =
        constructedStatement(
            () ->
                new SelectStatement(
                    distinct,
                    selection.expressions(),
                    hidden,
                    structure.fromClause(),
                    structure.where(),
                    structure.groupBy(),
                    structure.having(),
                    orderAst,
                    paginationAst));
    List<LogicalParameter> parameters = inputs.logicalParameters();
    validateLogicalParameters(parameters);
    validateStatementArguments(
        statementArguments,
        structure.parameterSlots().size(),
        parameters.size(),
        variant.pagination());
    ResolvedStatement compilationContext = prepareContext(statement);
    if (compilationContext.hasAggregateStructure()
        && (structure.validationSource() == null || !hidden.isEmpty())) {
      QueryInputMappings.validate(compilationContext, runtimeRegistry);
    }
    return plans.resolve(
        compilationContext,
        compilationContext.statement(),
        selected,
        variant,
        parameters,
        inputs.parameterSources,
        statementArguments.planArgument(),
        () -> renderPlan(compilationContext, parameters, selection.decoder()),
        observation);
  }

  private static List<SqlExpression<?>> selectionExpressions(
      CompiledQueryStructure structure, SelectedResult<?> selected) {
    return structure.selections().isEmpty() ? selected.expressions() : structure.selections();
  }

  /**
   * Validates original scopes before count, empty-IN pruning, or selected expression ordering
   * reuse.
   */
  private ResolvedStatement validateSelectionSource(
      SelectedResult<?> selected,
      CompiledQueryStructure structure,
      boolean distinct,
      TableRuntimeScope runtimeScope) {
    CompiledQueryStructure source = structure.validationStructure();
    TableRuntimeScope scope = runtimeScope.forFromClause(source.fromClause());
    List<SqlExpression<?>> expressions = selectionExpressions(source, selected);
    SelectStatement statement =
        constructedStatement(
            () ->
                new SelectStatement(
                    distinct,
                    expressions,
                    List.of(),
                    source.fromClause(),
                    source.where(),
                    source.groupBy(),
                    source.having(),
                    source.orderBy(),
                    null));
    ResolvedStatement resolved = prepareContext(statement);
    if (resolved.hasAggregateStructure()) {
      QueryInputMappings.validate(resolved, runtimeRegistry);
    }
    validateBuiltinParameterSources(source, runtimeScope);
    for (Selectable<?> parameterSource : source.parameterSources()) {
      if (!(parameterSource instanceof QueryColumn<?, ?>)) {
        runtimeScope.outputCodec(parameterSource);
      }
    }
    selected.resolve(scope, expressions);
    // The original description keeps its complete dialect capability validation even when the
    // final numbered/ordered/count statement prunes an operand or reuses a selected output.
    dialect.validate(resolved);
    return resolved;
  }

  private static void validateBuiltinParameterSources(
      CompiledQueryStructure structure, TableRuntimeScope runtimeScope) {
    for (Selectable<?> source : structure.builtinParameterSources()) {
      ResolvedValueMapping.requireBuiltinParameterSource(source, runtimeScope.outputCodec(source));
    }
  }

  private static List<OrderByItem> orderItems(
      CompiledQueryStructure structure, List<SortSpecification> specifications) {
    if (structure.orderBy().isEmpty()) {
      return specifications.stream().map(item -> QueryModels.sort(item).ast()).toList();
    }
    if (structure.orderBy().size() != specifications.size()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_COMPILED_SHAPE,
          "compiled ORDER BY item count does not match the query description");
    }
    return structure.orderBy();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ResolvedValueMapping<?> resolveValueMapping(
      Selectable<?> selectable, SqlExpression<?> expression, TableRuntimeScope scope) {
    return ResolvedValueMapping.resolve((Selectable) selectable, (SqlExpression) expression, scope);
  }

  private <E, R extends @Nullable Object> CompiledQueryPlan<R, Object> compilePlanFromProperties(
      EntityRuntimeModel<E> model,
      StatementAst statement,
      List<PropertyMeta<E, ?>> properties,
      RowDecoder<R> rowDecoder) {
    List<LogicalParameter> parameters = new ArrayList<>(properties.size());
    for (int ordinal = 0; ordinal < properties.size(); ordinal++) {
      PropertyMeta<E, ?> property = properties.get(ordinal);
      parameters.add(
          LogicalParameter.codec(
              expectedSlot(ordinal, property), model.property(property).codec()));
    }
    return compilePlan(statement, parameters, rowDecoder);
  }

  private <R extends @Nullable Object> CompiledQueryPlan<R, Object> compilePlan(
      StatementAst statement, List<LogicalParameter> logicalParameters, RowDecoder<R> rowDecoder) {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(rowDecoder, "rowDecoder");
    validateLogicalParameters(logicalParameters);
    return renderPlan(prepareContext(statement), logicalParameters, rowDecoder);
  }

  private <R extends @Nullable Object> CompiledQueryPlan<R, Object> renderPlan(
      ResolvedStatement compilationContext,
      List<LogicalParameter> logicalParameters,
      RowDecoder<R> rowDecoder) {
    SqlTemplate template;
    try {
      template = SqlCompiler.compile(compilationContext, dialect);
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_COMPILED_SHAPE, failure, "SQL rendering failed");
    }
    RenderedSql rendered = new RenderedSql(template.sql(), template.parameterLayout().slots());
    List<RenderedBinding> renderedBindings = renderedBindings(logicalParameters, rendered);
    int logicalParameterCount = logicalParameters.size();
    return new CompiledQueryPlan<>(
        dialect.id(),
        rendered,
        new QueryParameterBinder(renderedBindings, logicalParameterCount),
        rowDecoder);
  }

  private record QueryParameterBinder(List<RenderedBinding> bindings, int logicalParameterCount)
      implements ParameterBinder<Object> {
    @Override
    public int bind(
        PreparedStatement statement, int firstIndex, Object argument, JdbcWriteContext context)
        throws SQLException {
      var values = requireArguments(argument, logicalParameterCount);
      int index = firstIndex;
      for (RenderedBinding binding : bindings) {
        binding
            .parameter()
            .bind(
                statement,
                index,
                values == null ? argument : values.get(binding.argumentOrdinal()),
                context);
        index++;
      }
      return index;
    }

    @Override
    public int bindObserved(
        PreparedStatement statement,
        int firstIndex,
        Object argument,
        JdbcWriteContext context,
        ParameterBindingObserver observer)
        throws SQLException {
      var values = requireArguments(argument, logicalParameterCount);
      int index = firstIndex;
      boolean observing = observer.needsMore();
      for (RenderedBinding binding : bindings) {
        Object value = values == null ? argument : values.get(binding.argumentOrdinal());
        binding.parameter().bind(statement, index, value, context);
        if (observing) {
          observer.bound(index, binding.parameter().descriptor().javaType(), value);
          observing = observer.needsMore();
        }
        index++;
      }
      return index;
    }
  }

  private ResolvedStatement prepareContext(StatementAst statement) {
    try {
      return ResolvedStatement.analyze(statement);
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_COMPILED_SHAPE, failure, "query compilation context is invalid");
    }
  }

  private List<RenderedBinding> renderedBindings(
      List<LogicalParameter> logicalParameters, RenderedSql rendered) {
    List<RenderedBinding> bindings = new ArrayList<>(rendered.parameterCount());
    for (ParameterSlot<?> renderedSlot : rendered.parameters()) {
      int ordinal = renderedSlot.ordinal();
      if (ordinal < 0 || ordinal >= logicalParameters.size()) {
        throw unexpectedParameterShape();
      }
      LogicalParameter logical = logicalParameters.get(ordinal);
      if (!logical.matches(renderedSlot)) {
        throw unexpectedParameterShape();
      }
      bindings.add(new RenderedBinding(logical, ordinal));
    }
    return List.copyOf(bindings);
  }

  private SqlRenderException unexpectedParameterShape() {
    return SqlRenderException.of(
        SkisErrorCode.DIALECT_PARAMETER_SHAPE,
        dialect.id(),
        "dialect '" + dialect.id() + "' rendered an unexpected statement parameter shape");
  }

  private static void validateLogicalParameters(List<LogicalParameter> parameters) {
    for (int ordinal = 0; ordinal < parameters.size(); ordinal++) {
      LogicalParameter parameter = Objects.requireNonNull(parameters.get(ordinal), "parameter");
      if (parameter.descriptor().ordinal() != ordinal) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_LAYOUT,
            "logical parameter ordinals must be dense from zero; expected "
                + ordinal
                + " but found "
                + parameter.descriptor().ordinal());
      }
    }
  }

  private static <S extends StatementAst> S constructedStatement(Supplier<S> factory) {
    try {
      return factory.get();
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_COMPILED_SHAPE, failure, "AST construction failed");
    }
  }

  private static <E, V> ParameterSlot<V> expectedSlot(int ordinal, PropertyMeta<E, V> property) {
    return new ParameterSlot<>(
        ordinal,
        property.javaType(),
        SqlType.fromJavaType(property.javaType()),
        Nullability.NON_NULL);
  }

  private static @Nullable QueryArguments requireArguments(Object argument, int expectedCount) {
    Objects.requireNonNull(argument, "argument");
    if (expectedCount == 0) {
      if (argument != NoParameters.INSTANCE) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_LAYOUT, "compiled query does not accept parameters");
      }
      return null;
    }
    if (argument instanceof QueryArguments values) {
      if (values.size() != expectedCount) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_LAYOUT,
            "compiled query requires "
                + expectedCount
                + " logical parameters but received "
                + values.size());
      }
      return values;
    }
    if (expectedCount == 1 && argument != NoParameters.INSTANCE) {
      return null;
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_PARAMETER_LAYOUT,
        "compiled query requires " + expectedCount + " logical parameters");
  }

  private static void validateStatementArguments(
      QueryArguments arguments,
      int expectedOrdinaryCount,
      int expectedCount,
      QueryPaginationShape expectedPaginationShape) {
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(expectedPaginationShape, "expectedPaginationShape");
    if (arguments.size() != expectedCount) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_LAYOUT,
          "final statement requires "
              + expectedCount
              + " logical parameter values but received "
              + arguments.size());
    }
    if (arguments.ordinarySize() != expectedOrdinaryCount) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_LAYOUT,
          "final statement ordinary parameter value count must be "
              + expectedOrdinaryCount
              + " but was "
              + arguments.ordinarySize());
    }
    if (!arguments.paginationShape().equals(expectedPaginationShape)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PARAMETER_LAYOUT,
          "final statement pagination shape must be "
              + expectedPaginationShape
              + " but was "
              + arguments.paginationShape());
    }
  }

  private static <E> PredicateShape<E> equalityShape(
      QueryTable<E> table, @Nullable PropertyMeta<E, ?> property) {
    if (property == null) {
      return new PredicateShape<>(null, List.of());
    }
    return equalityShapeTyped(table, property);
  }

  private static <E, V> PredicateShape<E> equalityShapeTyped(
      QueryTable<E> table, PropertyMeta<E, V> property) {
    ColumnExpression<E, V> column = QueryTableModel.of(table).internalColumn(property).expression();
    ParameterSlot<V> slot =
        new ParameterSlot<>(0, property.javaType(), column.sqlType(), Nullability.NON_NULL);
    return new PredicateShape<>(column.eq(slot), List.of(property));
  }

  private static <E> void requireCanonicalModel(EntityRuntimeModel<E> model, QueryTable<E> table) {
    if (table.entity() != model.entity()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_METADATA_REQUIRED,
          "query table does not use the canonical runtime metadata of entity '"
              + model.entity().entityName()
              + "'");
    }
  }

  private record PredicateShape<E>(
      @Nullable SqlPredicate ast, List<PropertyMeta<E, ?>> properties) {

    private PredicateShape {
      properties = List.copyOf(properties);
    }
  }

  private record RenderedBinding(LogicalParameter parameter, int argumentOrdinal) {}

  enum ScalarBinding {
    NONE,
    INTEGER,
    LONG
  }

  record LogicalParameter(
      ParameterSlot<?> descriptor, @Nullable JdbcTypeCodec<?> codec, ScalarBinding scalarBinding) {

    LogicalParameter {
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(scalarBinding, "scalarBinding");
      if (codec != null && scalarBinding != ScalarBinding.NONE) {
        throw new IllegalArgumentException("a logical parameter cannot use two binder kinds");
      }
      if (codec == null && scalarBinding == ScalarBinding.NONE) {
        throw new IllegalArgumentException("a logical parameter requires one binder kind");
      }
    }

    static LogicalParameter codec(ParameterSlot<?> descriptor, JdbcTypeCodec<?> codec) {
      return new LogicalParameter(
          descriptor, Objects.requireNonNull(codec, "codec"), ScalarBinding.NONE);
    }

    static LogicalParameter integer(ParameterSlot<Integer> descriptor) {
      return new LogicalParameter(descriptor, null, ScalarBinding.INTEGER);
    }

    static LogicalParameter longValue(ParameterSlot<Long> descriptor) {
      return new LogicalParameter(descriptor, null, ScalarBinding.LONG);
    }

    boolean matches(ParameterSlot<?> slot) {
      return descriptor.ordinal() == slot.ordinal()
          && descriptor.javaType().equals(slot.javaType())
          && descriptor.sqlType() == slot.sqlType()
          && descriptor.nullability() == slot.nullability();
    }

    void bind(
        PreparedStatement statement, int index, @Nullable Object value, JdbcWriteContext context)
        throws SQLException {
      if (value == null && !descriptor.nullability().isNullable()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_VALUE,
            "non-null query parameter is null at JDBC parameter index " + index);
      }
      if (codec != null) {
        bindCodec(codec, descriptor.javaType(), statement, index, value, context);
        return;
      }
      switch (scalarBinding) {
        case INTEGER -> statement.setInt(index, requireType(value, Integer.class));
        case LONG -> statement.setLong(index, requireType(value, Long.class));
        case NONE ->
            throw QueryValidationException.of(
                SkisErrorCode.QUERY_PARAMETER_LAYOUT, "logical parameter has no binder");
      }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bindCodec(
        JdbcTypeCodec codec,
        Class<?> javaType,
        PreparedStatement statement,
        int index,
        @Nullable Object value,
        JdbcWriteContext context)
        throws SQLException {
      Objects.requireNonNull(statement, "statement");
      Objects.requireNonNull(context, "context");
      if (index < 1) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_LAYOUT, "JDBC parameter index must be positive");
      }
      if (value != null && !javaType.isInstance(value)) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_VALUE,
            "query parameter requires "
                + javaType.getTypeName()
                + " but received "
                + value.getClass().getTypeName());
      }
      codec.bind(statement, index, value, context);
    }

    private static <T> T requireType(@Nullable Object value, Class<T> type) {
      if (!type.isInstance(value)) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_LAYOUT,
            "pagination parameter requires "
                + type.getTypeName()
                + " but received "
                + (value == null ? "null" : value.getClass().getTypeName()));
      }
      return type.cast(value);
    }
  }

  private static final class InputsBuilder {

    private final TableRuntimeScope runtimeScope;
    private final List<LogicalParameter> logicalParameters = new ArrayList<>();
    private final List<@Nullable Selectable<?>> parameterSources = new ArrayList<>();

    private InputsBuilder(TableRuntimeScope runtimeScope, CompiledQueryStructure structure) {
      this.runtimeScope = Objects.requireNonNull(runtimeScope, "runtimeScope");
      if (structure.validationSource() == null) {
        validateBuiltinParameterSources(structure, runtimeScope);
      }
      for (int index = 0; index < structure.parameterSources().size(); index++) {
        Selectable<?> source = structure.parameterSources().get(index);
        addConditionMapping(source, structure.parameterSlots().get(index));
      }
    }

    private @Nullable SelectPagination pagination(
        List<SortSpecification> orderBy,
        List<OrderByItem> compiledOrder,
        QueryPagination pagination) {
      return switch (pagination) {
        case QueryPagination.None ignored -> null;
        case QueryPagination.LimitOnly ignored -> new Limit(addInteger());
        case QueryPagination.Offset ignored -> new OffsetLimit(addInteger(), addLong());
        case QueryPagination.Keyset keyset ->
            new KeysetSeek(keysetPredicate(orderBy, compiledOrder, keyset.values()), addInteger());
      };
    }

    private SqlPredicate keysetPredicate(
        List<SortSpecification> orderBy,
        List<OrderByItem> compiledOrder,
        List<@Nullable Object> values) {
      if (orderBy.size() != values.size()
          || compiledOrder.size() != orderBy.size()
          || orderBy.isEmpty()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_CONTINUATION_VALUE,
            "keyset continuation value count must match a non-empty ORDER BY");
      }
      @SuppressWarnings("unchecked")
      ParameterSlot<Object>[] slots = new ParameterSlot[values.size()];
      boolean[] nullable = new boolean[values.size()];
      for (int index = 0; index < values.size(); index++) {
        SortSpecification sort = orderBy.get(index);
        SqlExpression<?> expression = compiledOrder.get(index).expression();
        Object value = values.get(index);
        ResolvedValueMapping<?> mapping =
            resolveValueMapping(QueryModels.selectable(sort), expression, runtimeScope);
        nullable[index] = mapping.effectiveNullability().isNullable();
        if (nullable[index] && sort.nullPlacement() == NullPlacement.DIALECT_DEFAULT) {
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_CONTINUATION_VALUE,
              "effectively nullable keyset ordering expression '"
                  + SelectableSupport.summary(QueryModels.selectable(sort))
                  + "' must declare nullsFirst() or nullsLast()");
        }
        if (value == null) {
          if (!nullable[index]) {
            throw QueryValidationException.of(
                SkisErrorCode.QUERY_CONTINUATION_VALUE,
                "keyset continuation contains null for non-null expression '"
                    + SelectableSupport.summary(QueryModels.selectable(sort))
                    + "'");
          }
          continue;
        }
        if (!mapping.javaType().isInstance(value)) {
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_CONTINUATION_VALUE,
              "keyset continuation Java type does not match expression '"
                  + SelectableSupport.summary(QueryModels.selectable(sort))
                  + "'");
        }
        slots[index] = addValueMappingUntyped(mapping, QueryModels.selectable(sort));
      }

      List<SqlPredicate> disjunctions = new ArrayList<>();
      for (int index = 0; index < orderBy.size(); index++) {
        SqlPredicate after =
            after(
                orderBy.get(index),
                compiledOrder.get(index).expression(),
                values.get(index),
                slots[index],
                nullable[index]);
        if (after == null) {
          continue;
        }
        List<SqlPredicate> conjunctions = new ArrayList<>(index + 1);
        for (int prefix = 0; prefix < index; prefix++) {
          conjunctions.add(
              equal(compiledOrder.get(prefix).expression(), values.get(prefix), slots[prefix]));
        }
        conjunctions.add(after);
        disjunctions.add(combine(LogicalOperator.AND, conjunctions));
      }
      if (disjunctions.isEmpty()) {
        return falsePredicate(QueryModels.sort(orderBy.getFirst()).expression());
      }
      return combine(LogicalOperator.OR, disjunctions);
    }

    private ParameterSlot<Integer> addInteger() {
      int ordinal = logicalParameters.size();
      ParameterSlot<Integer> slot =
          new ParameterSlot<>(ordinal, Integer.class, SqlType.INTEGER, Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.integer(slot));
      parameterSources.add(null);
      return slot;
    }

    private ParameterSlot<Long> addLong() {
      int ordinal = logicalParameters.size();
      ParameterSlot<Long> slot =
          new ParameterSlot<>(ordinal, Long.class, SqlType.BIGINT, Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.longValue(slot));
      parameterSources.add(null);
      return slot;
    }

    private <V> ParameterSlot<V> addValueMapping(
        ResolvedValueMapping<V> mapping, Selectable<?> source) {
      int ordinal = logicalParameters.size();
      ParameterSlot<V> slot =
          new ParameterSlot<>(ordinal, mapping.javaType(), mapping.sqlType(), Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.codec(slot, mapping.codec()));
      parameterSources.add(source);
      return slot;
    }

    @SuppressWarnings("unchecked")
    private ParameterSlot<Object> addValueMappingUntyped(
        ResolvedValueMapping<?> mapping, Selectable<?> source) {
      return (ParameterSlot<Object>) addValueMapping(mapping, source);
    }

    private void addConditionMapping(Selectable<?> source, ParameterSlot<?> slot) {
      if (!slot.javaType().equals(source.javaType()) || slot.sqlType() != source.sqlType()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PARAMETER_LAYOUT,
            "query parameter slot descriptor does not match expression '"
                + SelectableSupport.summary(source)
                + "'");
      }
      logicalParameters.add(LogicalParameter.codec(slot, parameterCodec(source)));
      parameterSources.add(source);
    }

    private JdbcTypeCodec<?> parameterCodec(Selectable<?> source) {
      return runtimeScope.outputCodec(source);
    }

    private List<LogicalParameter> logicalParameters() {
      return List.copyOf(logicalParameters);
    }

    private static SqlPredicate equal(
        SqlExpression<?> expression, @Nullable Object value, @Nullable ParameterSlot<Object> slot) {
      return value == null
          ? new NullPredicate(expression, NullOperator.IS_NULL)
          : comparison(expression, ComparisonOperator.EQUAL, Objects.requireNonNull(slot, "slot"));
    }

    private static @Nullable SqlPredicate after(
        SortSpecification sort,
        SqlExpression<?> expression,
        @Nullable Object value,
        @Nullable ParameterSlot<Object> slot,
        boolean nullable) {
      if (value == null) {
        return sort.nullPlacement() == NullPlacement.FIRST
            ? new NullPredicate(expression, NullOperator.IS_NOT_NULL)
            : null;
      }
      ComparisonOperator operator =
          sort.direction() == SortDirection.ASC
              ? ComparisonOperator.GREATER_THAN
              : ComparisonOperator.LESS_THAN;
      SqlPredicate comparison =
          comparison(expression, operator, Objects.requireNonNull(slot, "slot"));
      if (nullable && sort.nullPlacement() == NullPlacement.LAST) {
        return new LogicalPredicate(
            LogicalOperator.OR,
            List.of(comparison, new NullPredicate(expression, NullOperator.IS_NULL)));
      }
      return comparison;
    }

    private static SqlPredicate combine(LogicalOperator operator, List<SqlPredicate> predicates) {
      return predicates.size() == 1
          ? predicates.getFirst()
          : new LogicalPredicate(operator, predicates);
    }

    private static SqlPredicate falsePredicate(SqlExpression<?> expression) {
      return emptyMembership(expression);
    }

    private static <V> SqlPredicate comparison(
        SqlExpression<V> expression, ComparisonOperator operator, ParameterSlot<?> slot) {
      if (!expression.javaType().equals(slot.javaType())
          || expression.sqlType() != slot.sqlType()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_CONTINUATION_VALUE,
            "keyset parameter slot does not match its ordering expression");
      }
      @SuppressWarnings("unchecked")
      ParameterSlot<V> typedSlot = (ParameterSlot<V>) slot;
      return new ComparisonPredicate<>(expression, operator, typedSlot);
    }

    private static <V> SqlPredicate emptyMembership(SqlExpression<V> expression) {
      return new InPredicate<>(expression, List.of(), false);
    }
  }
}
