package io.skis.sql.ast;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisDiagnostics.QuerySemanticReason;
import io.skis.core.SkisErrorCode;
import io.skis.metadata.PropertyMeta;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Query-block scope resolver used by the complete semantic-validation boundary. */
final class QueryScopeAnalyzer {

  private static final int MAX_PORTABLE_IDENTIFIER_BYTES = 63;

  private QueryScopeAnalyzer() {}

  static QueryBlockAnalysis analyzeTopLevel(SelectStatement statement) {
    return analyzeTopLevelStatement(Objects.requireNonNull(statement, "statement"));
  }

  static QueryBlockAnalysis analyzeTopLevel(CountAst statement) {
    return analyzeTopLevelStatement(Objects.requireNonNull(statement, "statement"));
  }

  private static QueryBlockAnalysis analyzeTopLevelStatement(StatementAst statement) {
    StatementParameters statementParameters = new StatementParameters();
    QueryBlockAnalysis analysis =
        new Analyzer(
                statement,
                QueryBlockPath.root(),
                QueryBlockAnalysis.ScopeSnapshot.empty(),
                statementParameters)
            .analyze();
    statementParameters.requireDense();
    return analysis;
  }

  static QueryBlockAnalysis analyzeNested(
      SelectStatement statement,
      QueryBlockPath path,
      QueryBlockAnalysis.ScopeSnapshot parentScope) {
    StatementParameters statementParameters = new StatementParameters();
    QueryBlockAnalysis analysis =
        new Analyzer(statement, path, parentScope, statementParameters).analyze();
    statementParameters.requireDense();
    return analysis;
  }

  private static QueryBlockAnalysis analyzeNested(
      SelectStatement statement,
      QueryBlockPath path,
      QueryBlockAnalysis.ScopeSnapshot parentScope,
      StatementParameters statementParameters) {
    return new Analyzer(statement, path, parentScope, statementParameters).analyze();
  }

  private static final class Analyzer {

    private final StatementAst statement;
    private final QueryBlockPath path;
    private final QueryBlockAnalysis.ScopeSnapshot parentScope;
    private final StatementParameters statementParameters;
    private final List<MutableSource> currentSources = new ArrayList<>();
    private final List<QueryBlockAnalysis.ScopeSource> allBlockSources;
    private final List<ResolvedExpression> expressions = new ArrayList<>();
    private final List<Resolution> groupKeys = new ArrayList<>();
    private final List<Resolution> groupedOutputs = new ArrayList<>();
    // Each clause expression owns one pair of sets; intermediate nodes retain no transitive sets.
    private Set<ResolvedColumnReference> expressionColumns = Set.of();
    private Set<ResolvedParameterIdentity> expressionParameters = Set.of();
    private final List<QueryBlockAnalysis.NestedBlock> nestedBlocks = new ArrayList<>();
    private final Map<QueryBlockAnalysis.ScopeSite, QueryBlockAnalysis.ScopeSnapshot> clauseScopes =
        new HashMap<>();
    private final Map<QueryBlockAnalysis.ScopeSite, ResolvedStructureKey> expressionKeys =
        new HashMap<>();
    private final Map<QueryBlockAnalysis.ScopeSite, Integer> nestedOrdinals = new HashMap<>();
    private final IdentityHashMap<DerivedRelationReference, QueryBlockAnalysis> derivedAnalyses =
        new IdentityHashMap<>();
    private @Nullable ExpressionPath invalidGroupKey;
    private int aggregateDepth;
    private boolean hasAggregate;
    private boolean hasSelectedAggregate;
    private boolean realSum;
    private boolean smallIntegerAverage;
    private boolean doubleAggregate;
    private boolean standardExpressionStructure;
    private boolean expressionNeedsLiteralTyping;
    private boolean retainGrouping;

    private Analyzer(
        StatementAst statement,
        QueryBlockPath path,
        QueryBlockAnalysis.ScopeSnapshot parentScope,
        StatementParameters statementParameters) {
      this.statement = Objects.requireNonNull(statement, "statement");
      if (!(statement instanceof SelectStatement) && !(statement instanceof CountAst)) {
        throw new IllegalArgumentException(
            "query-block analysis does not support statement node "
                + statement.getClass().getName());
      }
      this.path = Objects.requireNonNull(path, "path");
      this.parentScope = Objects.requireNonNull(parentScope, "parentScope");
      this.statementParameters = Objects.requireNonNull(statementParameters, "statementParameters");
      List<QueryBlockAnalysis.ScopeSource> allSources = new ArrayList<>();
      for (TableOccurrence occurrence : fromClause().occurrences()) {
        allSources.add(
            new QueryBlockAnalysis.ScopeSource(
                new ResolvedSourceIdentity(path, occurrence.occurrenceOrdinal()),
                occurrence.source(),
                false));
      }
      this.allBlockSources = List.copyOf(allSources);
    }

    private QueryBlockAnalysis analyze() {
      rememberScope(QueryClause.JOIN_SOURCE, 0, QueryBlockAnalysis.ScopeSnapshot.empty());
      resolveRelationSource(fromClause().root(), 0);
      registerSource(fromClause().occurrences().getFirst());
      for (int index = 0; index < joins().size(); index++) {
        JoinClause join = joins().get(index);
        int joinOrdinal = index + 1;
        rememberScope(QueryClause.JOIN_SOURCE, joinOrdinal, snapshot());
        resolveRelationSource(join.right(), joinOrdinal);
        registerSource(fromClause().occurrences().get(joinOrdinal));
        QueryBlockAnalysis.ScopeSnapshot onScope = snapshot();
        join.on()
            .ifPresent(
                predicate -> {
                  rememberScope(QueryClause.JOIN_ON, joinOrdinal, onScope);
                  resolveClauseExpression(predicate, QueryClause.JOIN_ON, joinOrdinal, onScope);
                });
        applyJoin(join.type(), joinOrdinal);
      }

      QueryBlockAnalysis.ScopeSnapshot finalScope = snapshot();
      for (int index = 0; index < selectionCount(); index++) {
        rememberScope(QueryClause.SELECT, index, finalScope);
        resolveClauseExpression(selection(index), QueryClause.SELECT, index, finalScope);
      }
      int hiddenOffset = selectionCount();
      for (int index = 0; index < hiddenSelections().size(); index++) {
        int itemOrdinal = hiddenOffset + index;
        rememberScope(QueryClause.SELECT, itemOrdinal, finalScope);
        resolveClauseExpression(
            hiddenSelections().get(index).expression(),
            QueryClause.SELECT,
            itemOrdinal,
            finalScope);
      }
      where()
          .ifPresent(
              where -> {
                rememberScope(QueryClause.WHERE, 0, finalScope);
                resolveClauseExpression(where, QueryClause.WHERE, 0, finalScope);
              });
      for (int index = 0; index < groupBy().size(); index++) {
        rememberScope(QueryClause.GROUP_BY, index, finalScope);
        resolveClauseExpression(groupBy().get(index), QueryClause.GROUP_BY, index, finalScope);
      }
      having()
          .ifPresent(
              having -> {
                rememberScope(QueryClause.HAVING, 0, finalScope);
                resolveClauseExpression(having, QueryClause.HAVING, 0, finalScope);
              });
      for (int index = 0; index < orderBy().size(); index++) {
        rememberScope(QueryClause.ORDER_BY, index, finalScope);
        resolveClauseExpression(
            orderBy().get(index).expression(), QueryClause.ORDER_BY, index, finalScope);
      }
      pagination().ifPresent(value -> resolvePagination(value, finalScope));
      validateGrouping();
      List<QueryBlockAnalysis.SourceOccurrence> occurrences = new ArrayList<>();
      for (MutableSource source : currentSources) {
        occurrences.add(
            new QueryBlockAnalysis.SourceOccurrence(
                source.identity, source.source, source.nullExtended));
      }
      boolean grouped = hasAggregate || !groupBy().isEmpty() || having().isPresent();
      boolean aggregateStructure = grouped;
      for (QueryBlockAnalysis.NestedBlock nested : nestedBlocks) {
        QueryBlockAnalysis child = nested.analysis();
        aggregateStructure |= child.hasAggregateStructure();
        standardExpressionStructure |= child.requiresStandardExpressionLowering();
        realSum |= child.hasRealSum();
        smallIntegerAverage |= child.hasSmallIntegerAverage();
        doubleAggregate |= child.hasDoubleAggregate();
      }
      QueryBlockAnalysis analysis =
          new QueryBlockAnalysis(
              path,
              occurrences,
              expressions,
              nestedBlocks,
              buildBlockKey(),
              clauseScopes,
              aggregateStructure,
              grouped || statement instanceof CountAst,
              standardExpressionStructure,
              realSum,
              smallIntegerAverage,
              doubleAggregate,
              atMostOneRow(grouped));
      if (statement instanceof SelectStatement select
          && select.pagination().isPresent()
          && !(select.pagination().orElseThrow() instanceof Limit)
          && select.orderBy().isEmpty()
          && !analysis.atMostOneRow()) {
        throw new IllegalArgumentException("paginated SELECT requires ORDER BY");
      }
      return analysis;
    }

    private boolean atMostOneRow(boolean grouped) {
      if (statement instanceof CountAst) {
        return true;
      }
      if (grouped) {
        boolean independent = true;
        for (Resolution key : groupKeys) {
          if (key.hasLocalColumns) {
            independent = false;
            break;
          }
        }
        if (independent) {
          return true;
        }
      }
      // Aggregates without column inputs still vary between groups (for example COUNT(*)).
      if (distinct() && !hasSelectedAggregate) {
        boolean independent = true;
        for (ResolvedExpression expression : expressions) {
          if (expression.position().clause() == QueryClause.SELECT
              && expression.hasLocalColumns(path)) {
            independent = false;
            break;
          }
        }
        if (independent) {
          return true;
        }
      }
      for (MutableSource source : currentSources) {
        if (source.source instanceof ParameterRelationSource) {
          continue;
        }
        if (!(source.source instanceof DerivedRelationSource derived)
            || !Objects.requireNonNull(derivedAnalyses.get(derived.reference()), "derived analysis")
                .atMostOneRow()) {
          return false;
        }
      }
      for (JoinClause join : joins()) {
        if (join.type() == JoinType.FULL) {
          return false;
        }
      }
      return true;
    }

    private void resolveRelationSource(RelationSource source, int sourceOrdinal) {
      if (source
          instanceof
          ParameterRelationSource(
              List<ParameterSlot<?>> parameters,
              DerivedRelationReference reference)) {
        requirePortableDerivedOutputs(reference, sourceOrdinal);
        for (int index = 0; index < parameters.size(); index++) {
          ParameterSlot<?> parameter = parameters.get(index);
          statementParameters.register(
              new ResolvedParameterIdentity(
                  path,
                  parameter.ordinal(),
                  parameter.javaType().getName(),
                  parameter.sqlType(),
                  parameter.nullability()),
              new ExpressionPath(path, QueryClause.JOIN_SOURCE, sourceOrdinal).operand(index));
        }
        return;
      }
      if (!(source instanceof DerivedRelationSource derived)) {
        return;
      }
      requirePortableDerivedOutputs(derived.reference(), sourceOrdinal);
      QueryBlockLocation location = QueryBlockLocation.relationSource(sourceOrdinal, 0);
      QueryBlockAnalysis child =
          QueryScopeAnalyzer.analyzeNested(
              derived.statement(),
              path.child(location),
              QueryBlockAnalysis.ScopeSnapshot.empty(),
              statementParameters);
      validateDerivedOutputNullability(derived, child);
      derivedAnalyses.put(derived.reference(), child);
      nestedBlocks.add(
          new QueryBlockAnalysis.NestedBlock(
              derived.statement(),
              location,
              QueryBlockAnalysis.NestedQueryKind.DERIVED_TABLE,
              child));
    }

    private void requirePortableDerivedOutputs(
        DerivedRelationReference reference, int sourceOrdinal) {
      for (int outputOrdinal = 0; outputOrdinal < reference.outputs().size(); outputOrdinal++) {
        String alias = reference.outputs().get(outputOrdinal).name().value();
        int byteLength = alias.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_PORTABLE_IDENTIFIER_BYTES) {
          throw new IllegalArgumentException(
              "query block "
                  + path
                  + " derived source occurrence #"
                  + sourceOrdinal
                  + " output #"
                  + outputOrdinal
                  + " alias '"
                  + alias
                  + "' uses "
                  + byteLength
                  + " UTF-8 bytes; portable derived output aliases must not exceed "
                  + MAX_PORTABLE_IDENTIFIER_BYTES
                  + " bytes because PostgreSQL truncates longer identifiers; use a shorter "
                  + "alias");
        }
      }
    }

    private void validateDerivedOutputNullability(
        DerivedRelationSource derived, QueryBlockAnalysis child) {
      for (int index = 0; index < derived.reference().outputs().size(); index++) {
        Nullability effective = selectionNullability(child, index);
        DerivedOutputColumn output = derived.reference().outputs().get(index);
        if (!output.nullability().isNullable() && effective.isNullable()) {
          throw new IllegalArgumentException(
              "derived relation alias '"
                  + derived.reference().alias().value()
                  + "' output '"
                  + output.name().value()
                  + "' is effectively nullable after its complete join chain; declare it with "
                  + "a nullable derived-output handle");
        }
      }
    }

    private void resolvePagination(
        SelectPagination pagination, QueryBlockAnalysis.ScopeSnapshot scope) {
      int itemOrdinal = 0;
      if (pagination instanceof KeysetSeek keyset) {
        rememberScope(QueryClause.PAGINATION, itemOrdinal, scope);
        resolveClauseExpression(keyset.predicate(), QueryClause.PAGINATION, itemOrdinal, scope);
        itemOrdinal++;
      }
      rememberScope(QueryClause.PAGINATION, itemOrdinal, scope);
      resolveClauseExpression(pagination.limit(), QueryClause.PAGINATION, itemOrdinal, scope);
      if (pagination instanceof OffsetLimit offset) {
        itemOrdinal++;
        rememberScope(QueryClause.PAGINATION, itemOrdinal, scope);
        resolveClauseExpression(offset.offset(), QueryClause.PAGINATION, itemOrdinal, scope);
      }
    }

    private void resolveClauseExpression(
        SqlExpression<?> expression,
        QueryClause clause,
        int itemOrdinal,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      ExpressionPath position = new ExpressionPath(path, clause, itemOrdinal);
      boolean precedingExpressionTyping = standardExpressionStructure;
      standardExpressionStructure = false;
      expressionNeedsLiteralTyping =
          clause == QueryClause.GROUP_BY && expression instanceof LiteralExpression<?>;
      if ((clause == QueryClause.SELECT || clause == QueryClause.GROUP_BY)
          && (expression instanceof ParameterSlot<?> || expression instanceof LiteralExpression<?>)
          && !(statement instanceof CountAst count && count.distinctExpression().isEmpty())) {
        standardExpressionStructure = true;
      }
      retainGrouping =
          switch (clause) {
            case SELECT, GROUP_BY, HAVING, ORDER_BY -> true;
            default -> false;
          };
      // Fresh sets avoid clearing an oversized table for every later small expression.
      expressionColumns = new LinkedHashSet<>();
      expressionParameters = new LinkedHashSet<>();
      Resolution resolution = resolveExpression(expression, position, scope);
      // Native expressions over columns do not need the dedicated typing traversal. Reuse
      // this clause's collected parameters and literal facts instead of rescanning its tree.
      standardExpressionStructure =
          precedingExpressionTyping
              || (standardExpressionStructure
                  && (!expressionParameters.isEmpty() || expressionNeedsLiteralTyping));
      if (resolution.effectiveNullability.isNullable() && expression.javaType().isPrimitive()) {
        throw new IllegalArgumentException(
            position
                + " is effectively nullable but uses primitive Java type "
                + expression.javaType().getTypeName());
      }
      ResolvedExpression resolved =
          new ResolvedExpression(
              position.root,
              resolution.key,
              List.copyOf(expressionColumns),
              List.copyOf(expressionParameters),
              resolution.effectiveNullability);
      expressions.add(resolved);
      if (clause == QueryClause.GROUP_BY) {
        // Boolean predicates also form parameterized row-dependent keys. Reuse the
        // dependencies collected above instead of inferring this from expression kinds.
        standardExpressionStructure |=
            resolution.hasLocalColumns && !expressionParameters.isEmpty();
        groupKeys.add(resolution);
      } else if (retainGrouping) {
        groupedOutputs.add(resolution);
      }
      expressionKeys.put(new QueryBlockAnalysis.ScopeSite(clause, itemOrdinal), resolution.key);
    }

    private Resolution resolveExpression(
        SqlExpression<?> expression,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      Objects.requireNonNull(expression, "expression");
      if (position.clause() == QueryClause.GROUP_BY
          && (expression instanceof SelectedOutputReference<?>
              || expression instanceof ScalarSubqueryExpression<?>
              || expression instanceof ExistsPredicate
              || expression instanceof InSubqueryPredicate<?>)) {
        rejectGroupKey(position);
      }
      return switch (expression) {
        case ColumnExpression<?, ?> column -> resolveColumn(column, position, scope);
        case DerivedColumnExpression<?> column -> resolveDerivedColumn(column, position, scope);
        case ParameterSlot<?> parameter -> resolveParameter(parameter, position);
        case AggregateExpression<?> aggregate -> resolveAggregate(aggregate, position, scope);
        case LiteralExpression<?> literal -> {
          expressionNeedsLiteralTyping |=
              literal.kind() == LiteralExpression.Kind.NULL
                  || (literal.sqlType() != SqlType.INTEGER && literal.sqlType() != SqlType.BOOLEAN);
          yield leafLiteral(List.of(literal.kind().name()), expression, literal.nullability());
        }
        case BooleanPredicate predicate ->
            leafLiteral(
                List.of("BOOLEAN", Boolean.toString(predicate.value())),
                expression,
                Nullability.NON_NULL);
        case SelectedOutputReference<?> reference ->
            leafLiteral(
                List.of("SELECTED_OUTPUT", Integer.toString(reference.position())),
                expression,
                reference.nullability());
        case ArithmeticExpression<?> arithmetic -> {
          standardExpressionStructure = true;
          yield binaryUnion(
              "ARITHMETIC",
              List.of(arithmetic.operator().name()),
              expression,
              resolveExpression(arithmetic.left(), position.operand(0), scope),
              resolveExpression(arithmetic.right(), position.operand(1), scope));
        }
        case ConcatExpression concat -> {
          standardExpressionStructure = true;
          yield variadic(
              "CONCAT",
              List.of(),
              expression,
              concat.operands(),
              position,
              scope,
              NullabilityMode.ANY_NULLABLE);
        }
        case CaseExpression<?> caseExpression -> {
          standardExpressionStructure = true;
          yield resolveCase(caseExpression, position, scope);
        }
        case CastExpression<?> cast -> {
          standardExpressionStructure = true;
          Resolution operand = resolveExpression(cast.operand(), position.operand(0), scope);
          var precision = cast.decimalPrecision();
          yield unary(
              "CAST",
              List.of(
                  cast.javaType().getName(),
                  cast.sqlType().name(),
                  precision == null ? "" : precision.toString()),
              expression,
              operand,
              operand.effectiveNullability);
        }
        case CoalesceExpression<?> coalesce -> {
          standardExpressionStructure = true;
          yield variadic(
              "COALESCE",
              List.of(),
              expression,
              coalesce.operands(),
              position,
              scope,
              NullabilityMode.ALL_NULLABLE);
        }
        case ComparisonPredicate<?> comparison -> {
          if ((comparison.left() instanceof ParameterSlot<?>
                  || comparison.left() instanceof LiteralExpression<?>)
              && (comparison.right() instanceof ParameterSlot<?>
                  || comparison.right() instanceof LiteralExpression<?>)) {
            standardExpressionStructure = true;
          }
          yield binaryUnion(
              "COMPARISON",
              List.of(comparison.operator().name()),
              expression,
              resolveExpression(comparison.left(), position.operand(0), scope),
              resolveExpression(comparison.right(), position.operand(1), scope));
        }
        case LogicalPredicate logical ->
            variadic(
                "LOGICAL",
                List.of(logical.operator().name()),
                expression,
                logical.operands(),
                position,
                scope,
                NullabilityMode.ANY_NULLABLE);
        case NullPredicate nullPredicate -> {
          if (nullPredicate.operand() instanceof ParameterSlot<?>
              || nullPredicate.operand() instanceof LiteralExpression<?>) {
            standardExpressionStructure = true;
          }
          Resolution operand =
              resolveExpression(nullPredicate.operand(), position.operand(0), scope);
          yield unary(
              "NULL_PREDICATE",
              List.of(nullPredicate.operator().name()),
              expression,
              operand,
              Nullability.NON_NULL);
        }
        case BetweenPredicate<?> between ->
            variadicResolved(
                "BETWEEN",
                List.of(),
                expression,
                List.of(
                    resolveExpression(between.value(), position.operand(0), scope),
                    resolveExpression(between.lower(), position.operand(1), scope),
                    resolveExpression(between.upper(), position.operand(2), scope)),
                NullabilityMode.UNION);
        case LikePredicate like ->
            binaryUnion(
                "LIKE",
                List.of(),
                expression,
                resolveExpression(like.value(), position.operand(0), scope),
                resolveExpression(like.pattern(), position.operand(1), scope));
        case InPredicate<?> in -> resolveIn(in, position, scope);
        case InSubqueryPredicate<?> in -> resolveInSubquery(in, position, scope);
        case ExistsPredicate exists -> resolveExists(exists, position, scope);
        case ScalarSubqueryExpression<?> scalar -> resolveScalarSubquery(scalar, position, scope);
        case NotPredicate not -> {
          Resolution operand = resolveExpression(not.operand(), position.operand(0), scope);
          yield unary("NOT", List.of(), expression, operand, operand.effectiveNullability);
        }
        case IncrementExpression<?> increment -> {
          standardExpressionStructure = true;
          Resolution operand = resolveExpression(increment.operand(), position.operand(0), scope);
          yield unary("INCREMENT", List.of(), expression, operand, operand.effectiveNullability);
        }
        default ->
            throw new IllegalArgumentException(
                position
                    + " uses unsupported SQL expression node "
                    + expression.getClass().getName());
      };
    }

    private Resolution resolveColumn(
        ColumnExpression<?, ?> column,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      List<QueryBlockAnalysis.ScopeFrame> frames = scope.frames();
      QueryBlockAnalysis.ScopeSource target = null;
      int targetFrame = -1;
      for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
        for (QueryBlockAnalysis.ScopeSource source : frames.get(frameIndex).sources()) {
          if (source.entityTableOrNull() == column.table()) {
            target = source;
            targetFrame = frameIndex;
            break;
          }
        }
        if (target != null) {
          break;
        }
      }
      if (target == null) {
        QueryBlockAnalysis.ScopeSource unavailable = findUnavailable(column.table(), frames);
        if (unavailable != null) {
          throw scopeFailure(
              position,
              column,
              "references query block "
                  + unavailable.identity().blockPath()
                  + " source occurrence #"
                  + unavailable.identity().occurrenceOrdinal()
                  + " before it is visible");
        }
        String reason;
        if (path.locations().isEmpty()) {
          reason = "is an unresolved outer reference in a top-level query block";
        } else if (parentScope.frames().isEmpty()) {
          reason = "crosses a non-correlated relation-source boundary";
        } else {
          reason = "is not visible in the current or any ancestor query-block scope";
        }
        throw scopeFailure(position, column, reason);
      }
      if (targetFrame > 0) {
        rejectHarmfulQualifierShadow(position, column, target, targetFrame, frames);
        if (position.clause() == QueryClause.GROUP_BY) {
          rejectGroupKey(position);
        }
      }

      PropertyMeta<?, ?> property = column.property();
      ResolvedColumnIdentity identity =
          new ResolvedColumnIdentity(
              target.identity(),
              property.ordinal(),
              property.name(),
              property.column().name(),
              property.javaType().getName(),
              column.sqlType());
      Nullability nullability = column.nullability().union(Nullability.of(target.nullExtended()));
      expressionColumns.add(identity);
      return resolution(
          new ResolvedStructureKey.Atom(
              "COLUMN",
              concat(
                  descriptor(column),
                  List.of(
                      identity.source().blockPath().toString(),
                      Integer.toString(identity.source().occurrenceOrdinal()),
                      Integer.toString(identity.propertyOrdinal()),
                      identity.propertyName(),
                      identity.columnName()))),
          nullability,
          List.of(),
          position,
          identity,
          null);
    }

    private Resolution resolveDerivedColumn(
        DerivedColumnExpression<?> column,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      List<QueryBlockAnalysis.ScopeFrame> frames = scope.frames();
      QueryBlockAnalysis.ScopeSource target = null;
      int targetFrame = -1;
      for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
        for (QueryBlockAnalysis.ScopeSource source : frames.get(frameIndex).sources()) {
          if (source.derivedReferenceOrNull() == column.relation()) {
            target = source;
            targetFrame = frameIndex;
            break;
          }
        }
        if (target != null) {
          break;
        }
      }
      if (target == null) {
        QueryBlockAnalysis.ScopeSource unavailable = findUnavailable(column.relation(), frames);
        if (unavailable != null) {
          throw derivedScopeFailure(
              position,
              column,
              "references query block "
                  + unavailable.identity().blockPath()
                  + " source occurrence #"
                  + unavailable.identity().occurrenceOrdinal()
                  + " before it is visible");
        }
        String reason;
        if (path.locations().isEmpty()) {
          reason = "is an unresolved outer reference in a top-level query block";
        } else if (parentScope.frames().isEmpty()) {
          reason = "crosses a non-correlated relation-source boundary";
        } else {
          reason = "is not visible in the current or any ancestor query-block scope";
        }
        throw derivedScopeFailure(position, column, reason);
      }
      if (targetFrame > 0) {
        rejectHarmfulQualifierShadow(position, column, target, targetFrame, frames);
        if (position.clause() == QueryClause.GROUP_BY) {
          rejectGroupKey(position);
        }
      }

      if (target.source() instanceof ParameterRelationSource parameters) {
        // A single non-correlated VALUES row contributes no row-dependent grouping identity.
        return resolveParameter(parameters.parameters().get(column.outputOrdinal()), position);
      }
      DerivedOutputColumn output = column.output();
      ResolvedDerivedColumnIdentity identity =
          new ResolvedDerivedColumnIdentity(
              target.identity(),
              column.outputOrdinal(),
              output.name().value(),
              output.javaType().getName(),
              output.sqlType());
      Nullability nullability = output.nullability().union(Nullability.of(target.nullExtended()));
      expressionColumns.add(identity);
      return resolution(
          new ResolvedStructureKey.Atom(
              "DERIVED_COLUMN",
              concat(
                  descriptor(column),
                  List.of(
                      identity.source().blockPath().toString(),
                      Integer.toString(identity.source().occurrenceOrdinal()),
                      Integer.toString(identity.outputOrdinal()),
                      identity.outputName()))),
          nullability,
          List.of(),
          position,
          identity,
          null);
    }

    private Resolution resolveParameter(ParameterSlot<?> parameter, ExpressionPath position) {
      ResolvedParameterIdentity identity =
          new ResolvedParameterIdentity(
              path,
              parameter.ordinal(),
              parameter.javaType().getName(),
              parameter.sqlType(),
              parameter.nullability());
      statementParameters.register(identity, position);
      expressionParameters.add(identity);
      return new Resolution(
          new ResolvedStructureKey.Atom(
              "PARAMETER",
              List.of(
                  path.toString(),
                  Integer.toString(parameter.ordinal()),
                  parameter.javaType().getName(),
                  parameter.sqlType().name(),
                  parameter.nullability().name())),
          parameter.nullability());
    }

    private Resolution resolveCase(
        CaseExpression<?> expression,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      List<Resolution> children = new ArrayList<>();
      List<Resolution> results = new ArrayList<>();
      int operand = 0;
      for (CaseWhen<?> branch : expression.branches()) {
        children.add(resolveExpression(branch.condition(), position.operand(operand++), scope));
        Resolution result = resolveExpression(branch.result(), position.operand(operand++), scope);
        children.add(result);
        results.add(result);
      }
      if (expression.otherwise().isPresent()) {
        Resolution otherwise =
            resolveExpression(
                expression.otherwise().orElseThrow(), position.operand(operand), scope);
        children.add(otherwise);
        results.add(otherwise);
      }
      Nullability nullability =
          expression.otherwise().isEmpty()
              ? Nullability.NULLABLE
              : combineNullability(results, NullabilityMode.ANY_NULLABLE);
      return combine("CASE", List.of(), expression, children, nullability);
    }

    private Resolution resolveAggregate(
        AggregateExpression<?> aggregate,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      if (position.clause() != QueryClause.SELECT
          && position.clause() != QueryClause.HAVING
          && position.clause() != QueryClause.ORDER_BY) {
        throw aggregateFailure(aggregate, position, QuerySemanticReason.AGGREGATE_IN_CLAUSE);
      }
      if (aggregateDepth != 0 || statement instanceof CountAst) {
        throw aggregateFailure(aggregate, position, QuerySemanticReason.NESTED_AGGREGATE);
      }
      Resolution operand = null;
      aggregateDepth++;
      try {
        if (aggregate.operand().isPresent()) {
          operand =
              resolveExpression(aggregate.operand().orElseThrow(), position.operand(0), scope);
        }
      } finally {
        aggregateDepth--;
      }
      if (operand != null && operand.hasColumns && !operand.hasLocalColumns) {
        throw aggregateFailure(aggregate, position, QuerySemanticReason.OUTER_ONLY_AGGREGATE);
      }
      hasAggregate = true;
      hasSelectedAggregate |= position.clause() == QueryClause.SELECT;
      SqlExpression<?> input = aggregate.operand().orElse(null);
      if (input instanceof ParameterSlot<?> || input instanceof LiteralExpression<?>) {
        standardExpressionStructure = true;
      }
      if (input != null) {
        realSum |=
            aggregate.kind() == AggregateExpression.Kind.SUM && input.sqlType() == SqlType.REAL;
        smallIntegerAverage |=
            aggregate.kind() == AggregateExpression.Kind.AVG
                && (input.sqlType() == SqlType.TINYINT
                    || input.sqlType() == SqlType.SMALLINT
                    || input.sqlType() == SqlType.INTEGER);
        doubleAggregate |=
            (aggregate.kind() == AggregateExpression.Kind.SUM
                    || aggregate.kind() == AggregateExpression.Kind.AVG)
                && input.sqlType() == SqlType.DOUBLE;
      }
      List<Resolution> children = operand == null ? List.of() : List.of(operand);
      return new Resolution(
          new ResolvedStructureKey.Node(
              "AGGREGATE",
              concat(
                  descriptor(aggregate),
                  List.of(
                      aggregate.kind().name(),
                      Boolean.toString(aggregate.distinct()),
                      path.toString())),
              operand == null ? List.of() : List.of(operand.key)),
          aggregate.nullability(),
          children,
          position,
          aggregate.kind(),
          null,
          null);
    }

    private void rejectGroupKey(ExpressionPath position) {
      if (invalidGroupKey == null) {
        invalidGroupKey = position;
      }
    }

    private void validateGrouping() {
      if (invalidGroupKey != null) {
        throw groupingFailure(invalidGroupKey, QuerySemanticReason.INVALID_GROUP_KEY, null);
      }
      if (groupBy().isEmpty() && having().isEmpty() && !hasAggregate) {
        return;
      }
      Set<Resolution> keys =
          groupBy().isEmpty() ? new HashSet<>() : HashSet.newHashSet(groupBy().size());
      Set<ResolvedColumnReference> columns =
          groupBy().isEmpty() ? new HashSet<>() : HashSet.newHashSet(groupBy().size());
      for (int index = 0; index < groupKeys.size(); index++) {
        Resolution resolution = groupKeys.get(index);
        if (!keys.add(resolution)) {
          throw groupingFailure(
              new ExpressionPath(path, QueryClause.GROUP_BY, index),
              QuerySemanticReason.DUPLICATE_GROUP_KEY,
              null);
        }
        if (resolution.column != null) {
          columns.add(resolution.column);
        }
      }
      for (Resolution output : groupedOutputs) {
        requireGrouped(output, keys, columns);
      }
    }

    private void requireGrouped(
        Resolution resolution, Set<Resolution> keys, Set<ResolvedColumnReference> groupedColumns) {
      // Ancestor-only dependencies are checked at their owning block's embedding position.
      if (resolution.aggregateKind != null
          || !resolution.hasLocalColumns
          || (!resolution.containsSubquery && !keys.isEmpty() && keys.contains(resolution))) {
        return;
      }
      if (resolution.column != null) {
        requireGroupedColumn(resolution.column, resolution.position, groupedColumns);
      }
      for (Resolution child : resolution.children) {
        requireGrouped(child, keys, groupedColumns);
      }
      if (resolution.nested != null) {
        for (ResolvedColumnReference dependency : resolution.nested.correlatedColumns) {
          requireGroupedColumn(dependency, resolution.position, groupedColumns);
        }
      }
    }

    private void requireGroupedColumn(
        ResolvedColumnReference dependency,
        @Nullable ExpressionPath position,
        Set<ResolvedColumnReference> groupedColumns) {
      if (dependency.source().blockPath().equals(path) && !groupedColumns.contains(dependency)) {
        throw groupingFailure(
            Objects.requireNonNull(position, "column dependency position"),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            dependency);
      }
    }

    private QueryValidationException groupingFailure(
        ExpressionPath position,
        QuerySemanticReason reason,
        @Nullable ResolvedColumnReference missing) {
      SkisDiagnostics.QueryLocation dependency =
          missing == null
              ? null
              : new SkisDiagnostics.QueryLocation(
                  missing.source().blockPath().toString(),
                  null,
                  missing.source().occurrenceOrdinal(),
                  null,
                  null);
      Integer propertyOrdinal =
          missing instanceof ResolvedColumnIdentity column ? column.propertyOrdinal() : null;
      Integer outputOrdinal =
          missing instanceof ResolvedDerivedColumnIdentity column ? column.outputOrdinal() : null;
      return semanticFailure(
          SkisErrorCode.QUERY_GROUPING_CONTRACT,
          position,
          new SkisDiagnostics.QuerySemantics(
              reason, null, null, dependency, propertyOrdinal, outputOrdinal));
    }

    private QueryValidationException aggregateFailure(
        AggregateExpression<?> aggregate, ExpressionPath position, QuerySemanticReason reason) {
      return semanticFailure(
          SkisErrorCode.QUERY_AGGREGATE_CONTRACT,
          position,
          new SkisDiagnostics.QuerySemantics(
              reason, aggregate.kind().name(), path.toString(), null, null, null));
    }

    private QueryValidationException semanticFailure(
        SkisErrorCode code, ExpressionPath position, SkisDiagnostics.QuerySemantics semantics) {
      return QueryValidationException.of(
          code,
          SkisDiagnostics.builder(code.defaultStage())
              .queryBlock(position.blockPath().toString())
              .clause(position.clause().displayName())
              .expressionPath(position.toString())
              .querySemantics(semantics)
              .detail(code.description())
              .build());
    }

    private Resolution resolveIn(
        InPredicate<?> expression,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      List<Resolution> children = new ArrayList<>(expression.candidates().size() + 1);
      children.add(resolveExpression(expression.value(), position.operand(0), scope));
      for (int index = 0; index < expression.candidates().size(); index++) {
        children.add(
            resolveExpression(
                expression.candidates().get(index), position.operand(index + 1), scope));
      }
      Nullability nullability =
          expression.candidates().isEmpty()
              ? Nullability.NON_NULL
              : combineNullability(children, NullabilityMode.ANY_NULLABLE);
      return combine(
          "IN", List.of(Boolean.toString(expression.negated())), expression, children, nullability);
    }

    private Resolution resolveExists(
        ExistsPredicate expression,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      NestedResolution nested =
          resolveNested(
              expression.subquery(), position, scope, QueryBlockAnalysis.NestedQueryKind.EXISTS);
      return resolution(
          new ResolvedStructureKey.Node(
              "EXISTS",
              concat(
                  descriptor(expression),
                  List.of(Boolean.toString(expression.negated()), nested.location.toString())),
              List.of(nested.analysis.structureKey())),
          Nullability.NON_NULL,
          List.of(),
          position,
          null,
          nested);
    }

    private Resolution resolveInSubquery(
        InSubqueryPredicate<?> expression,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      SemanticValidator.validateInSubquery(expression.value(), expression.subquery());
      Resolution value = resolveExpression(expression.value(), position.operand(0), scope);
      NestedResolution nested =
          resolveNested(
              expression.subquery(),
              position,
              scope,
              QueryBlockAnalysis.NestedQueryKind.IN_SUBQUERY);
      Nullability outputNullability = selectionNullability(nested.analysis);
      return resolution(
          new ResolvedStructureKey.Node(
              "IN_SUBQUERY",
              concat(
                  descriptor(expression),
                  List.of(Boolean.toString(expression.negated()), nested.location.toString())),
              List.of(value.key, nested.analysis.structureKey())),
          value.effectiveNullability.union(outputNullability),
          List.of(value),
          position,
          null,
          nested);
    }

    private Resolution resolveScalarSubquery(
        ScalarSubqueryExpression<?> expression,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      SemanticValidator.validateScalarSubquery(expression.subquery());
      NestedResolution nested =
          resolveNested(
              expression.subquery(),
              position,
              scope,
              QueryBlockAnalysis.NestedQueryKind.SCALAR_SUBQUERY);
      return resolution(
          new ResolvedStructureKey.Node(
              "SCALAR_SUBQUERY",
              concat(descriptor(expression), List.of(nested.location.toString())),
              List.of(nested.analysis.structureKey())),
          Nullability.NULLABLE,
          List.of(),
          position,
          null,
          nested);
    }

    private NestedResolution resolveNested(
        SelectStatement subquery,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope,
        QueryBlockAnalysis.NestedQueryKind kind) {
      QueryBlockAnalysis.ScopeSite site =
          new QueryBlockAnalysis.ScopeSite(position.clause(), position.itemOrdinal());
      int nestedOrdinal = nestedOrdinals.getOrDefault(site, 0);
      nestedOrdinals.put(site, nestedOrdinal + 1);
      QueryBlockLocation location =
          new QueryBlockLocation(position.clause(), position.itemOrdinal(), nestedOrdinal);
      QueryBlockAnalysis child =
          QueryScopeAnalyzer.analyzeNested(
              subquery, path.child(location), scope, statementParameters);
      nestedBlocks.add(new QueryBlockAnalysis.NestedBlock(subquery, location, kind, child));

      LinkedHashSet<ResolvedColumnReference> correlatedColumns = new LinkedHashSet<>();
      LinkedHashSet<ResolvedParameterIdentity> parameters = new LinkedHashSet<>();
      boolean hasLocalColumns = false;
      for (ResolvedExpression resolved : child.expressions()) {
        for (ResolvedColumnReference dependency : resolved.columnDependencies()) {
          if (!dependency.source().blockPath().equals(child.path())) {
            correlatedColumns.add(dependency);
            hasLocalColumns |= dependency.source().blockPath().equals(path);
          }
        }
        parameters.addAll(resolved.parameterDependencies());
      }
      expressionColumns.addAll(correlatedColumns);
      expressionParameters.addAll(parameters);
      return new NestedResolution(location, child, correlatedColumns, hasLocalColumns);
    }

    private Nullability selectionNullability(QueryBlockAnalysis child) {
      return selectionNullability(child, 0);
    }

    private Nullability selectionNullability(QueryBlockAnalysis child, int outputOrdinal) {
      for (ResolvedExpression resolved : child.expressions()) {
        ExpressionPosition childPosition = resolved.position();
        if (childPosition.clause() == QueryClause.SELECT
            && childPosition.itemOrdinal() == outputOrdinal
            && childPosition.operandPath().isEmpty()) {
          return resolved.effectiveNullability();
        }
      }
      throw new IllegalStateException(
          "nested query block " + child.path() + " has no resolved SELECT item #" + outputOrdinal);
    }

    private Resolution leafLiteral(
        List<String> attributes, SqlExpression<?> expression, Nullability nullability) {
      return new Resolution(
          new ResolvedStructureKey.Atom("LITERAL", concat(descriptor(expression), attributes)),
          nullability);
    }

    private Resolution unary(
        String kind,
        List<String> attributes,
        SqlExpression<?> expression,
        Resolution operand,
        Nullability nullability) {
      return combine(kind, attributes, expression, List.of(operand), nullability);
    }

    private Resolution binaryUnion(
        String kind,
        List<String> attributes,
        SqlExpression<?> expression,
        Resolution left,
        Resolution right) {
      List<Resolution> children = List.of(left, right);
      return combine(
          kind,
          attributes,
          expression,
          children,
          combineNullability(children, NullabilityMode.UNION));
    }

    private Resolution variadic(
        String kind,
        List<String> attributes,
        SqlExpression<?> expression,
        List<? extends SqlExpression<?>> operands,
        ExpressionPath position,
        QueryBlockAnalysis.ScopeSnapshot scope,
        NullabilityMode mode) {
      List<Resolution> children = new ArrayList<>(operands.size());
      for (int index = 0; index < operands.size(); index++) {
        children.add(resolveExpression(operands.get(index), position.operand(index), scope));
      }
      return variadicResolved(kind, attributes, expression, children, mode);
    }

    private Resolution variadicResolved(
        String kind,
        List<String> attributes,
        SqlExpression<?> expression,
        List<Resolution> children,
        NullabilityMode mode) {
      return combine(kind, attributes, expression, children, combineNullability(children, mode));
    }

    private Resolution combine(
        String kind,
        List<String> attributes,
        SqlExpression<?> expression,
        List<Resolution> children,
        Nullability nullability) {
      List<ResolvedStructureKey> childKeys = new ArrayList<>(children.size());
      for (Resolution child : children) {
        childKeys.add(child.key);
      }
      return resolution(
          new ResolvedStructureKey.Node(
              kind, concat(descriptor(expression), attributes), childKeys),
          nullability,
          children,
          null,
          null,
          null);
    }

    private Resolution resolution(
        ResolvedStructureKey key,
        Nullability nullability,
        List<Resolution> children,
        @Nullable ExpressionPath position,
        @Nullable ResolvedColumnReference column,
        @Nullable NestedResolution nested) {
      // Row-level clauses still resolve every node, but need no grouping tree or leaf positions.
      return retainGrouping
          ? new Resolution(key, nullability, children, position, null, column, nested)
          : new Resolution(key, nullability);
    }

    private void registerSource(TableOccurrence occurrence) {
      RelationSource source = occurrence.source();
      requirePortableQualifier(occurrence, source.effectiveQualifier());
      TableExpression<?> table = source.entityTable().orElse(null);
      if (table != null) {
        for (QueryBlockAnalysis.ScopeFrame frame : parentScope.frames()) {
          for (QueryBlockAnalysis.ScopeSource ancestor : frame.sources()) {
            if (ancestor.entityTableOrNull() == table) {
              throw new IllegalArgumentException(
                  "query block "
                      + path
                      + " source occurrence #"
                      + occurrence.occurrenceOrdinal()
                      + " reuses the same table-expression object already visible as "
                      + ancestor.identity()
                      + "; create an independent alias instance");
            }
          }
        }
      }
      DerivedRelationReference derivedReference = source.derivedReference().orElse(null);
      if (derivedReference != null) {
        for (QueryBlockAnalysis.ScopeFrame frame : parentScope.frames()) {
          for (QueryBlockAnalysis.ScopeSource ancestor : frame.sources()) {
            if (ancestor.derivedReferenceOrNull() == derivedReference) {
              throw new IllegalArgumentException(
                  "query block "
                      + path
                      + " source occurrence #"
                      + occurrence.occurrenceOrdinal()
                      + " reuses the same derived-relation reference already visible as "
                      + ancestor.identity()
                      + "; create an independently aliased relation occurrence");
            }
          }
        }
      }
      currentSources.add(
          new MutableSource(
              new ResolvedSourceIdentity(path, occurrence.occurrenceOrdinal()), source));
    }

    private void requirePortableQualifier(TableOccurrence occurrence, String qualifier) {
      int byteLength = qualifier.getBytes(StandardCharsets.UTF_8).length;
      if (byteLength > MAX_PORTABLE_IDENTIFIER_BYTES) {
        throw new IllegalArgumentException(
            "query block "
                + path
                + " source occurrence #"
                + occurrence.occurrenceOrdinal()
                + " effective qualifier '"
                + qualifier
                + "' uses "
                + byteLength
                + " UTF-8 bytes; portable query qualifiers must not exceed "
                + MAX_PORTABLE_IDENTIFIER_BYTES
                + " bytes because PostgreSQL truncates longer identifiers; use a shorter alias");
      }
    }

    private void applyJoin(JoinType type, int rightOrdinal) {
      MutableSource right = currentSources.get(rightOrdinal);
      switch (type) {
        case INNER, CROSS -> {}
        case LEFT -> right.nullExtended = true;
        case RIGHT -> markLeftNullable(rightOrdinal);
        case FULL -> {
          markLeftNullable(rightOrdinal);
          right.nullExtended = true;
        }
      }
    }

    private void markLeftNullable(int rightOrdinal) {
      for (int index = 0; index < rightOrdinal; index++) {
        currentSources.get(index).nullExtended = true;
      }
    }

    private QueryBlockAnalysis.ScopeSnapshot snapshot() {
      List<QueryBlockAnalysis.ScopeSource> sources = new ArrayList<>(currentSources.size());
      for (MutableSource source : currentSources) {
        sources.add(
            new QueryBlockAnalysis.ScopeSource(
                source.identity, source.source, source.nullExtended));
      }
      return parentScope.prepend(new QueryBlockAnalysis.ScopeFrame(path, sources, allBlockSources));
    }

    private void rememberScope(
        QueryClause clause, int itemOrdinal, QueryBlockAnalysis.ScopeSnapshot scope) {
      clauseScopes.put(new QueryBlockAnalysis.ScopeSite(clause, itemOrdinal), scope);
    }

    private void rejectHarmfulQualifierShadow(
        ExpressionPath position,
        ColumnExpression<?, ?> column,
        QueryBlockAnalysis.ScopeSource target,
        int targetFrame,
        List<QueryBlockAnalysis.ScopeFrame> frames) {
      String qualifier = target.source().effectiveQualifier();
      for (int frameIndex = 0; frameIndex < targetFrame; frameIndex++) {
        for (QueryBlockAnalysis.ScopeSource nearer : frames.get(frameIndex).sources()) {
          if (nearer.source().effectiveQualifier().equals(qualifier)) {
            throw scopeFailure(
                position,
                column,
                "targets ancestor "
                    + target.identity()
                    + " but effective qualifier '"
                    + qualifier
                    + "' is shadowed by nearer "
                    + nearer.identity());
          }
        }
      }
    }

    private void rejectHarmfulQualifierShadow(
        ExpressionPath position,
        DerivedColumnExpression<?> column,
        QueryBlockAnalysis.ScopeSource target,
        int targetFrame,
        List<QueryBlockAnalysis.ScopeFrame> frames) {
      String qualifier = target.source().effectiveQualifier();
      for (int frameIndex = 0; frameIndex < targetFrame; frameIndex++) {
        for (QueryBlockAnalysis.ScopeSource nearer : frames.get(frameIndex).sources()) {
          if (nearer.source().effectiveQualifier().equals(qualifier)) {
            throw derivedScopeFailure(
                position,
                column,
                "targets ancestor "
                    + target.identity()
                    + " but effective qualifier '"
                    + qualifier
                    + "' is shadowed by nearer "
                    + nearer.identity());
          }
        }
      }
    }

    private QueryBlockAnalysis.@Nullable ScopeSource findUnavailable(
        TableExpression<?> table, List<QueryBlockAnalysis.ScopeFrame> frames) {
      for (QueryBlockAnalysis.ScopeFrame frame : frames) {
        for (QueryBlockAnalysis.ScopeSource source : frame.allBlockSources()) {
          boolean visible =
              frame.sources().stream()
                  .anyMatch(candidate -> candidate.identity().equals(source.identity()));
          if (source.entityTableOrNull() == table && !visible) {
            return source;
          }
        }
      }
      return null;
    }

    private QueryBlockAnalysis.@Nullable ScopeSource findUnavailable(
        DerivedRelationReference relation, List<QueryBlockAnalysis.ScopeFrame> frames) {
      for (QueryBlockAnalysis.ScopeFrame frame : frames) {
        for (QueryBlockAnalysis.ScopeSource source : frame.allBlockSources()) {
          boolean visible =
              frame.sources().stream()
                  .anyMatch(candidate -> candidate.identity().equals(source.identity()));
          if (source.derivedReferenceOrNull() == relation && !visible) {
            return source;
          }
        }
      }
      return null;
    }

    private IllegalArgumentException scopeFailure(
        ExpressionPath position, ColumnExpression<?, ?> column, String reason) {
      String qualifier =
          column
              .table()
              .alias()
              .map(Identifier::value)
              .orElse(column.table().entity().table().name());
      String sourceDescription =
          column
              .table()
              .alias()
              .map(
                  alias ->
                      "entity '"
                          + column.table().entity().entityName()
                          + "', alias '"
                          + alias.value()
                          + "'")
              .orElse(
                  "entity '"
                      + column.table().entity().entityName()
                      + "', unaliased qualifier '"
                      + qualifier
                      + "'");
      return new IllegalArgumentException(
          position
              + " column '"
              + qualifier
              + '.'
              + column.property().name()
              + "' ("
              + sourceDescription
              + ") "
              + reason
              + "; table references are matched by object identity");
    }

    private IllegalArgumentException derivedScopeFailure(
        ExpressionPath position, DerivedColumnExpression<?> column, String reason) {
      return new IllegalArgumentException(
          position
              + " derived column '"
              + column.relation().alias().value()
              + '.'
              + column.output().name().value()
              + "' "
              + reason
              + "; derived relation references are matched by object identity");
    }

    private ResolvedStructureKey buildBlockKey() {
      List<ResolvedStructureKey> sourceKeys = new ArrayList<>(currentSources.size());
      for (MutableSource source : currentSources) {
        sourceKeys.add(sourceKey(source));
      }

      List<ResolvedStructureKey> joinKeys = new ArrayList<>(joins().size());
      for (int index = 0; index < joins().size(); index++) {
        int joinOrdinal = index + 1;
        JoinClause join = joins().get(index);
        ResolvedStructureKey on =
            expressionKeys.get(new QueryBlockAnalysis.ScopeSite(QueryClause.JOIN_ON, joinOrdinal));
        joinKeys.add(
            new ResolvedStructureKey.Node(
                "JOIN",
                List.of(join.type().name(), Integer.toString(joinOrdinal)),
                on == null
                    ? List.of(sourceKeys.get(joinOrdinal))
                    : List.of(sourceKeys.get(joinOrdinal), on)));
      }

      List<ResolvedStructureKey> children = new ArrayList<>();
      children.add(
          new ResolvedStructureKey.Node("FROM", List.of(), List.of(sourceKeys.getFirst())));
      children.add(new ResolvedStructureKey.Node("JOINS", List.of(), joinKeys));
      children.add(sectionKeyOffset0(QueryClause.SELECT, selectionCount()));
      children.add(hiddenSelectionsKey());
      children.add(optionalKeyItemOrdinal0(QueryClause.WHERE));
      children.add(sectionKeyOffset0(QueryClause.GROUP_BY, groupBy().size()));
      children.add(optionalKeyItemOrdinal0(QueryClause.HAVING));
      children.add(orderByKey());
      children.add(paginationKey());
      return new ResolvedStructureKey.Node(
          "SELECT_BLOCK", List.of(path.toString(), Boolean.toString(distinct())), children);
    }

    private ResolvedStructureKey sourceKey(MutableSource source) {
      return switch (source.source) {
        case EntityRelationSource entity -> {
          var metadata = entity.table().entity();
          var table = metadata.table();
          yield new ResolvedStructureKey.Atom(
              "ENTITY_SOURCE",
              List.of(
                  source.identity.blockPath().toString(),
                  Integer.toString(source.identity.occurrenceOrdinal()),
                  metadata.javaType().getName(),
                  metadata.entityName(),
                  metadata.mode().name(),
                  table.catalog(),
                  table.schema(),
                  table.name(),
                  entity.table().alias().map(Identifier::value).orElse("")));
        }
        case DerivedRelationSource derived -> {
          QueryBlockAnalysis child = derivedAnalyses.get(derived.reference());
          if (child == null) {
            throw new IllegalStateException(
                "derived source '"
                    + derived.reference().alias().value()
                    + "' has no resolved child query block");
          }
          yield new ResolvedStructureKey.Node(
              "DERIVED_SOURCE",
              derivedSourceAttributes(source.identity, derived.reference()),
              List.of(child.structureKey()));
        }
        case ParameterRelationSource parameters -> {
          List<ResolvedStructureKey> slots = new ArrayList<>(parameters.parameters().size());
          for (ParameterSlot<?> parameter : parameters.parameters()) {
            slots.add(
                new ResolvedStructureKey.Atom(
                    "PARAMETER",
                    List.of(
                        Integer.toString(parameter.ordinal()), parameter.javaType().getName(),
                        parameter.sqlType().name(), parameter.nullability().name())));
          }
          yield new ResolvedStructureKey.Node(
              "PARAMETER_SOURCE",
              derivedSourceAttributes(source.identity, parameters.reference()),
              slots);
        }
      };
    }

    private static List<String> derivedSourceAttributes(
        ResolvedSourceIdentity identity, DerivedRelationReference reference) {
      List<DerivedOutputColumn> outputs = reference.outputs();
      List<String> attributes = new ArrayList<>(3 + outputs.size() * 5);
      attributes.add(identity.blockPath().toString());
      attributes.add(Integer.toString(identity.occurrenceOrdinal()));
      attributes.add(reference.alias().value());
      for (int index = 0; index < outputs.size(); index++) {
        DerivedOutputColumn output = outputs.get(index);
        attributes.add(Integer.toString(index));
        attributes.add(output.name().value());
        attributes.add(output.javaType().getName());
        attributes.add(output.sqlType().name());
        attributes.add(output.nullability().name());
      }
      return attributes;
    }

    private ResolvedStructureKey sectionKeyOffset0(QueryClause clause, int size) {
      List<ResolvedStructureKey> items = new ArrayList<>(size);
      for (int index = 0; index < size; index++) {
        ResolvedStructureKey key =
            expressionKeys.get(new QueryBlockAnalysis.ScopeSite(clause, index));
        if (key == null) {
          throw new IllegalStateException(
              "missing resolved expression key for " + clause.displayName() + " item #" + index);
        }
        items.add(key);
      }
      return new ResolvedStructureKey.Node(clause.name(), List.of(), items);
    }

    private ResolvedStructureKey hiddenSelectionsKey() {
      List<ResolvedStructureKey> items = new ArrayList<>(hiddenSelections().size());
      int offset = selectionCount();
      for (int index = 0; index < hiddenSelections().size(); index++) {
        HiddenSelection hidden = hiddenSelections().get(index);
        ResolvedStructureKey key =
            expressionKeys.get(
                new QueryBlockAnalysis.ScopeSite(QueryClause.SELECT, offset + index));
        if (key == null) {
          throw new IllegalStateException(
              "missing resolved expression key for hidden SELECT item #" + index);
        }
        items.add(
            new ResolvedStructureKey.Node(
                "HIDDEN_SELECTION", List.of(hidden.alias().value()), List.of(key)));
      }
      return new ResolvedStructureKey.Node("HIDDEN_SELECTIONS", List.of(), items);
    }

    private ResolvedStructureKey orderByKey() {
      List<ResolvedStructureKey> items = new ArrayList<>(orderBy().size());
      for (int index = 0; index < orderBy().size(); index++) {
        OrderByItem item = orderBy().get(index);
        ResolvedStructureKey key =
            expressionKeys.get(new QueryBlockAnalysis.ScopeSite(QueryClause.ORDER_BY, index));
        if (key == null) {
          throw new IllegalStateException(
              "missing resolved expression key for ORDER BY item #" + index);
        }
        items.add(
            new ResolvedStructureKey.Node(
                "ORDER_BY_ITEM",
                List.of(item.direction().name(), item.nullOrder().name()),
                List.of(key)));
      }
      return new ResolvedStructureKey.Node("ORDER_BY", List.of(), items);
    }

    private ResolvedStructureKey optionalKeyItemOrdinal0(QueryClause clause) {
      ResolvedStructureKey key = expressionKeys.get(new QueryBlockAnalysis.ScopeSite(clause, 0));
      return new ResolvedStructureKey.Node(
          clause.name(), List.of(), key == null ? List.of() : List.of(key));
    }

    private ResolvedStructureKey paginationKey() {
      List<ResolvedStructureKey> items = new ArrayList<>();
      for (int index = 0; ; index++) {
        ResolvedStructureKey key =
            expressionKeys.get(new QueryBlockAnalysis.ScopeSite(QueryClause.PAGINATION, index));
        if (key == null) {
          break;
        }
        items.add(key);
      }
      return new ResolvedStructureKey.Node(
          "PAGINATION",
          pagination().map(value -> List.of(value.getClass().getSimpleName())).orElse(List.of()),
          items);
    }

    private FromClause fromClause() {
      return switch (statement) {
        case SelectStatement select -> select.fromClause();
        case CountAst count -> count.fromClause();
        default -> throw unsupportedStatement();
      };
    }

    private List<JoinClause> joins() {
      return fromClause().joins();
    }

    private int selectionCount() {
      return statement instanceof SelectStatement select ? select.selections().size() : 1;
    }

    private SqlExpression<?> selection(int index) {
      return switch (statement) {
        case SelectStatement select -> select.selections().get(index);
        case CountAst count -> {
          if (index != 0) {
            throw new IndexOutOfBoundsException(index);
          }
          yield count.distinctExpression().orElse(LiteralExpression.trueLiteral());
        }
        default -> throw unsupportedStatement();
      };
    }

    private List<HiddenSelection> hiddenSelections() {
      return statement instanceof SelectStatement select ? select.hiddenSelections() : List.of();
    }

    private Optional<SqlPredicate> where() {
      return switch (statement) {
        case SelectStatement select -> select.where();
        case CountAst count -> count.predicate();
        default -> throw unsupportedStatement();
      };
    }

    private List<SqlExpression<?>> groupBy() {
      return statement instanceof SelectStatement select ? select.groupBy() : List.of();
    }

    private Optional<SqlPredicate> having() {
      return statement instanceof SelectStatement select ? select.having() : Optional.empty();
    }

    private List<OrderByItem> orderBy() {
      return statement instanceof SelectStatement select ? select.orderBy() : List.of();
    }

    private Optional<SelectPagination> pagination() {
      return statement instanceof SelectStatement select ? select.pagination() : Optional.empty();
    }

    private boolean distinct() {
      return statement instanceof SelectStatement select && select.distinct();
    }

    private IllegalStateException unsupportedStatement() {
      return new IllegalStateException(
          "query-block analysis does not support statement node " + statement.getClass().getName());
    }
  }

  private enum NullabilityMode {
    UNION,
    ANY_NULLABLE,
    ALL_NULLABLE
  }

  private static Nullability combineNullability(List<Resolution> children, NullabilityMode mode) {
    if (children.isEmpty()) {
      return mode == NullabilityMode.ALL_NULLABLE ? Nullability.NULLABLE : Nullability.NON_NULL;
    }
    return switch (mode) {
      case UNION, ANY_NULLABLE ->
          children.stream().anyMatch(child -> child.effectiveNullability.isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
      case ALL_NULLABLE ->
          children.stream().allMatch(child -> child.effectiveNullability.isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
    };
  }

  private static List<String> descriptor(SqlExpression<?> expression) {
    return List.of(
        expression.javaType().getName(),
        expression.sqlType().name(),
        expression.nullability().name());
  }

  private static <T> List<T> concat(List<T> first, List<T> second) {
    List<T> result = new ArrayList<>(first.size() + second.size());
    result.addAll(first);
    result.addAll(second);
    return List.copyOf(result);
  }

  private static final class MutableSource {
    private final ResolvedSourceIdentity identity;
    private final RelationSource source;
    private boolean nullExtended;

    private MutableSource(ResolvedSourceIdentity identity, RelationSource source) {
      this.identity = Objects.requireNonNull(identity, "identity");
      this.source = Objects.requireNonNull(source, "source");
    }
  }

  private static final class StatementParameters {

    private final Map<Integer, ResolvedParameterIdentity> parametersByOrdinal = new HashMap<>();

    private void register(ResolvedParameterIdentity identity, ExpressionPath position) {
      ResolvedParameterIdentity existing =
          parametersByOrdinal.putIfAbsent(identity.parameterOrdinal(), identity);
      if (existing != null
          && (!existing.javaTypeName().equals(identity.javaTypeName())
              || existing.sqlType() != identity.sqlType()
              || existing.nullability() != identity.nullability())) {
        throw new IllegalArgumentException(
            position
                + " parameter ordinal "
                + identity.parameterOrdinal()
                + " conflicts with an earlier Java type, SQL type, or nullability descriptor");
      }
    }

    private void requireDense() {
      for (int expected = 0; expected < parametersByOrdinal.size(); expected++) {
        if (!parametersByOrdinal.containsKey(expected)) {
          throw new IllegalArgumentException(
              "statement parameter ordinals must be contiguous from zero; missing ordinal "
                  + expected);
        }
      }
    }
  }

  /** Shared operand prefixes keep traversal and retained leaf paths linear in the node count. */
  private static final class ExpressionPath {
    private final ExpressionPosition root;
    private final @Nullable ExpressionPath parent;
    private final int ordinal;
    private final int depth;

    private ExpressionPath(QueryBlockPath blockPath, QueryClause clause, int itemOrdinal) {
      root = new ExpressionPosition(blockPath, clause, itemOrdinal, List.of());
      parent = null;
      ordinal = -1;
      depth = 0;
    }

    private ExpressionPath(ExpressionPath parent, int ordinal) {
      root = parent.root;
      this.parent = parent;
      this.ordinal = ordinal;
      depth = parent.depth + 1;
    }

    private ExpressionPath operand(int ordinal) {
      return new ExpressionPath(this, ordinal);
    }

    private QueryBlockPath blockPath() {
      return root.blockPath();
    }

    private QueryClause clause() {
      return root.clause();
    }

    private int itemOrdinal() {
      return root.itemOrdinal();
    }

    @Override
    public String toString() {
      if (depth == 0) {
        return root.toString();
      }
      // Only a failure formats operand paths; successful analysis publishes the clause root.
      int[] ordinals = new int[depth];
      ExpressionPath current = this;
      for (int index = depth - 1; index >= 0; index--) {
        ordinals[index] = current.ordinal;
        current = Objects.requireNonNull(current.parent, "operand path parent");
      }
      StringBuilder text = new StringBuilder(root.toString());
      for (int operand : ordinals) {
        text.append(" operand #").append(operand);
      }
      return text.toString();
    }
  }

  /** Analysis-local facts reuse resolved operands; they never escape into a shared plan or AST. */
  private static final class Resolution {
    private final ResolvedStructureKey key;
    private final Nullability effectiveNullability;
    private final List<Resolution> children;
    private final @Nullable ExpressionPath position;
    private final AggregateExpression.@Nullable Kind aggregateKind;
    private final @Nullable ResolvedColumnReference column;
    private final @Nullable NestedResolution nested;
    private final boolean hasColumns;
    private final boolean hasLocalColumns;
    private final boolean containsSubquery;
    private int groupingHash;
    private boolean groupingHashComputed;

    private Resolution(ResolvedStructureKey key, Nullability effectiveNullability) {
      this(key, effectiveNullability, List.of(), null, null, null, null);
    }

    private Resolution(
        ResolvedStructureKey key,
        Nullability effectiveNullability,
        List<Resolution> children,
        @Nullable ExpressionPath position,
        AggregateExpression.@Nullable Kind aggregateKind,
        @Nullable ResolvedColumnReference column,
        @Nullable NestedResolution nested) {
      this.key = Objects.requireNonNull(key, "key");
      this.effectiveNullability =
          Objects.requireNonNull(effectiveNullability, "effectiveNullability");
      this.children = children;
      this.position = position;
      this.aggregateKind = aggregateKind;
      this.column = column;
      this.nested = nested;
      boolean columns = column != null || (nested != null && !nested.correlatedColumns.isEmpty());
      boolean localColumns = nested != null && nested.hasLocalColumns;
      if (column != null) {
        QueryBlockPath blockPath = Objects.requireNonNull(position, "column position").blockPath();
        localColumns |= column.source().blockPath().equals(blockPath);
      }
      boolean hasSubquery = nested != null;
      for (Resolution child : children) {
        columns |= child.hasColumns;
        localColumns |= child.hasLocalColumns;
        hasSubquery |= child.containsSubquery;
      }
      this.containsSubquery = hasSubquery;
      this.hasColumns = columns;
      this.hasLocalColumns = localColumns;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return this == other || other instanceof Resolution that && key.equals(that.key);
    }

    @Override
    public int hashCode() {
      // Ordinary queries never request grouping hashes. Each required subtree is hashed once.
      if (!groupingHashComputed) {
        groupingHash =
            switch (key) {
              case ResolvedStructureKey.Atom atom -> atom.hashCode();
              case ResolvedStructureKey.Node node -> {
                // These summaries omit child facts; grouping never hashes them, but equals still
                // requires the same hash for the same key if another internal use requests one.
                if (containsSubquery || children.size() != node.children().size()) {
                  yield node.hashCode();
                }
                int childrenHash = 1;
                for (Resolution child : children) {
                  childrenHash = 31 * childrenHash + child.hashCode();
                }
                yield 31 * (31 * node.kind().hashCode() + node.attributes().hashCode())
                    + childrenHash;
              }
            };
        groupingHashComputed = true;
      }
      return groupingHash;
    }
  }

  private record NestedResolution(
      QueryBlockLocation location,
      QueryBlockAnalysis analysis,
      LinkedHashSet<ResolvedColumnReference> correlatedColumns,
      boolean hasLocalColumns) {

    private NestedResolution {
      Objects.requireNonNull(location, "location");
      Objects.requireNonNull(analysis, "analysis");
      correlatedColumns = new LinkedHashSet<>(correlatedColumns);
    }
  }
}
