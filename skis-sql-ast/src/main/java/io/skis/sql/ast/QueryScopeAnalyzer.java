package io.skis.sql.ast;

import io.skis.metadata.PropertyMeta;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query-block scope resolver used by the complete semantic-validation boundary. */
final class QueryScopeAnalyzer {

  private static final int MAX_PORTABLE_QUALIFIER_BYTES = 63;

  private QueryScopeAnalyzer() {}

  static QueryBlockAnalysis analyzeTopLevel(SelectStatement statement) {
    return new Analyzer(
            Objects.requireNonNull(statement, "statement"),
            QueryBlockPath.root(),
            QueryBlockAnalysis.ScopeSnapshot.empty())
        .analyze();
  }

  static QueryBlockAnalysis analyzeNested(
      SelectStatement statement,
      QueryBlockPath path,
      QueryBlockAnalysis.ScopeSnapshot parentScope) {
    return new Analyzer(statement, path, parentScope).analyze();
  }

  private static final class Analyzer {

    private final SelectStatement statement;
    private final QueryBlockPath path;
    private final QueryBlockAnalysis.ScopeSnapshot parentScope;
    private final List<MutableSource> currentSources = new ArrayList<>();
    private final List<QueryBlockAnalysis.ScopeSource> allBlockSources;
    private final Map<Integer, ResolvedParameterIdentity> parametersByOrdinal = new HashMap<>();
    private final List<ResolvedExpression> expressions = new ArrayList<>();
    private final Map<QueryBlockAnalysis.ScopeSite, QueryBlockAnalysis.ScopeSnapshot> clauseScopes =
        new HashMap<>();
    private final Map<QueryBlockAnalysis.ScopeSite, ResolvedStructureKey> expressionKeys =
        new HashMap<>();

    private Analyzer(
        SelectStatement statement,
        QueryBlockPath path,
        QueryBlockAnalysis.ScopeSnapshot parentScope) {
      this.statement = Objects.requireNonNull(statement, "statement");
      this.path = Objects.requireNonNull(path, "path");
      this.parentScope = Objects.requireNonNull(parentScope, "parentScope");
      List<QueryBlockAnalysis.ScopeSource> allSources = new ArrayList<>();
      for (TableOccurrence occurrence : statement.fromClause().occurrences()) {
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
      registerSource(statement.fromClause().occurrences().getFirst());
      for (int index = 0; index < statement.joins().size(); index++) {
        JoinClause join = statement.joins().get(index);
        int joinOrdinal = index + 1;
        rememberScope(QueryClause.JOIN_SOURCE, joinOrdinal, snapshot());
        registerSource(statement.fromClause().occurrences().get(joinOrdinal));
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
      for (int index = 0; index < statement.selections().size(); index++) {
        rememberScope(QueryClause.SELECT, index, finalScope);
        resolveClauseExpression(
            statement.selections().get(index), QueryClause.SELECT, index, finalScope);
      }
      int hiddenOffset = statement.selections().size();
      for (int index = 0; index < statement.hiddenSelections().size(); index++) {
        int itemOrdinal = hiddenOffset + index;
        rememberScope(QueryClause.SELECT, itemOrdinal, finalScope);
        resolveClauseExpression(
            statement.hiddenSelections().get(index).expression(),
            QueryClause.SELECT,
            itemOrdinal,
            finalScope);
      }
      statement
          .where()
          .ifPresent(
              where -> {
                rememberScope(QueryClause.WHERE, 0, finalScope);
                resolveClauseExpression(where, QueryClause.WHERE, 0, finalScope);
              });
      for (int index = 0; index < statement.groupBy().size(); index++) {
        rememberScope(QueryClause.GROUP_BY, index, finalScope);
        resolveClauseExpression(
            statement.groupBy().get(index), QueryClause.GROUP_BY, index, finalScope);
      }
      statement
          .having()
          .ifPresent(
              having -> {
                rememberScope(QueryClause.HAVING, 0, finalScope);
                resolveClauseExpression(having, QueryClause.HAVING, 0, finalScope);
              });
      for (int index = 0; index < statement.orderBy().size(); index++) {
        rememberScope(QueryClause.ORDER_BY, index, finalScope);
        resolveClauseExpression(
            statement.orderBy().get(index).expression(), QueryClause.ORDER_BY, index, finalScope);
      }
      statement.pagination().ifPresent(pagination -> resolvePagination(pagination, finalScope));
      requireDenseParameterOrdinals();

      List<QueryBlockAnalysis.SourceOccurrence> occurrences = new ArrayList<>();
      for (MutableSource source : currentSources) {
        occurrences.add(
            new QueryBlockAnalysis.SourceOccurrence(
                source.identity, source.source, source.nullExtended));
      }
      return new QueryBlockAnalysis(path, occurrences, expressions, buildBlockKey(), clauseScopes);
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
      ExpressionPosition position = new ExpressionPosition(path, clause, itemOrdinal, List.of());
      Resolution resolution = resolveExpression(expression, position, scope);
      if (resolution.effectiveNullability.isNullable() && expression.javaType().isPrimitive()) {
        throw new IllegalArgumentException(
            position
                + " is effectively nullable but uses primitive Java type "
                + expression.javaType().getTypeName());
      }
      ResolvedExpression resolved =
          new ResolvedExpression(
              position,
              resolution.key,
              List.copyOf(resolution.columns),
              List.copyOf(resolution.parameters),
              resolution.effectiveNullability);
      expressions.add(resolved);
      expressionKeys.put(new QueryBlockAnalysis.ScopeSite(clause, itemOrdinal), resolution.key);
    }

    private Resolution resolveExpression(
        SqlExpression<?> expression,
        ExpressionPosition position,
        QueryBlockAnalysis.ScopeSnapshot scope) {
      Objects.requireNonNull(expression, "expression");
      return switch (expression) {
        case ColumnExpression<?, ?> column -> resolveColumn(column, position, scope);
        case ParameterSlot<?> parameter -> resolveParameter(parameter, position);
        case LiteralExpression<?> literal ->
            leafLiteral(List.of(literal.kind().name()), expression, literal.nullability());
        case ArithmeticExpression<?> arithmetic ->
            binaryUnion(
                "ARITHMETIC",
                List.of(arithmetic.operator().name()),
                expression,
                resolveExpression(arithmetic.left(), position.operand(0), scope),
                resolveExpression(arithmetic.right(), position.operand(1), scope));
        case ConcatExpression concat ->
            variadic(
                "CONCAT",
                List.of(),
                expression,
                concat.operands(),
                position,
                scope,
                NullabilityMode.ANY_NULLABLE);
        case CaseExpression<?> caseExpression -> resolveCase(caseExpression, position, scope);
        case CastExpression<?> cast -> {
          Resolution operand = resolveExpression(cast.operand(), position.operand(0), scope);
          yield unary(
              "CAST",
              List.of(cast.javaType().getName(), cast.sqlType().name()),
              expression,
              operand,
              operand.effectiveNullability);
        }
        case CoalesceExpression<?> coalesce ->
            variadic(
                "COALESCE",
                List.of(),
                expression,
                coalesce.operands(),
                position,
                scope,
                NullabilityMode.ALL_NULLABLE);
        case ComparisonPredicate<?> comparison ->
            binaryUnion(
                "COMPARISON",
                List.of(comparison.operator().name()),
                expression,
                resolveExpression(comparison.left(), position.operand(0), scope),
                resolveExpression(comparison.right(), position.operand(1), scope));
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
        case NotPredicate not -> {
          Resolution operand = resolveExpression(not.operand(), position.operand(0), scope);
          yield unary("NOT", List.of(), expression, operand, operand.effectiveNullability);
        }
        case IncrementExpression<?> increment -> {
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
        ExpressionPosition position,
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
      return new Resolution(
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
          linkedSet(identity),
          new LinkedHashSet<>(),
          nullability);
    }

    private Resolution resolveParameter(ParameterSlot<?> parameter, ExpressionPosition position) {
      ResolvedParameterIdentity identity =
          new ResolvedParameterIdentity(
              path,
              parameter.ordinal(),
              parameter.javaType().getName(),
              parameter.sqlType(),
              parameter.nullability());
      ResolvedParameterIdentity existing =
          parametersByOrdinal.putIfAbsent(parameter.ordinal(), identity);
      if (existing != null
          && (!existing.javaTypeName().equals(identity.javaTypeName())
              || existing.sqlType() != identity.sqlType()
              || existing.nullability() != identity.nullability())) {
        throw new IllegalArgumentException(
            position
                + " parameter ordinal "
                + parameter.ordinal()
                + " conflicts with an earlier Java type, SQL type, or nullability descriptor");
      }
      return new Resolution(
          new ResolvedStructureKey.Atom(
              "PARAMETER",
              List.of(
                  path.toString(),
                  Integer.toString(parameter.ordinal()),
                  parameter.javaType().getName(),
                  parameter.sqlType().name(),
                  parameter.nullability().name())),
          new LinkedHashSet<>(),
          linkedSet(identity),
          parameter.nullability());
    }

    private Resolution resolveCase(
        CaseExpression<?> expression,
        ExpressionPosition position,
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

    private Resolution resolveIn(
        InPredicate<?> expression,
        ExpressionPosition position,
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

    private Resolution leafLiteral(
        List<String> attributes, SqlExpression<?> expression, Nullability nullability) {
      return new Resolution(
          new ResolvedStructureKey.Atom("LITERAL", concat(descriptor(expression), attributes)),
          new LinkedHashSet<>(),
          new LinkedHashSet<>(),
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
        ExpressionPosition position,
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
      LinkedHashSet<ResolvedColumnIdentity> columns = new LinkedHashSet<>();
      LinkedHashSet<ResolvedParameterIdentity> parameters = new LinkedHashSet<>();
      List<ResolvedStructureKey> childKeys = new ArrayList<>(children.size());
      for (Resolution child : children) {
        columns.addAll(child.columns);
        parameters.addAll(child.parameters);
        childKeys.add(child.key);
      }
      return new Resolution(
          new ResolvedStructureKey.Node(
              kind, concat(descriptor(expression), attributes), childKeys),
          columns,
          parameters,
          nullability);
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
      currentSources.add(
          new MutableSource(
              new ResolvedSourceIdentity(path, occurrence.occurrenceOrdinal()), source));
    }

    private void requirePortableQualifier(TableOccurrence occurrence, String qualifier) {
      int byteLength = qualifier.getBytes(StandardCharsets.UTF_8).length;
      if (byteLength > MAX_PORTABLE_QUALIFIER_BYTES) {
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
                + MAX_PORTABLE_QUALIFIER_BYTES
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
        ExpressionPosition position,
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

    private IllegalArgumentException scopeFailure(
        ExpressionPosition position, ColumnExpression<?, ?> column, String reason) {
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

    private void requireDenseParameterOrdinals() {
      for (int expected = 0; expected < parametersByOrdinal.size(); expected++) {
        if (!parametersByOrdinal.containsKey(expected)) {
          throw new IllegalArgumentException(
              "query block "
                  + path
                  + " parameter ordinals must be contiguous from zero; missing ordinal "
                  + expected);
        }
      }
    }

    private ResolvedStructureKey buildBlockKey() {
      List<ResolvedStructureKey> sourceKeys = new ArrayList<>(currentSources.size());
      for (MutableSource source : currentSources) {
        sourceKeys.add(sourceKey(source));
      }

      List<ResolvedStructureKey> joinKeys = new ArrayList<>(statement.joins().size());
      for (int index = 0; index < statement.joins().size(); index++) {
        int joinOrdinal = index + 1;
        JoinClause join = statement.joins().get(index);
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
      children.add(sectionKeyOffset0(QueryClause.SELECT, statement.selections().size()));
      children.add(hiddenSelectionsKey());
      children.add(optionalKeyItemOrdinal0(QueryClause.WHERE));
      children.add(sectionKeyOffset0(QueryClause.GROUP_BY, statement.groupBy().size()));
      children.add(optionalKeyItemOrdinal0(QueryClause.HAVING));
      children.add(orderByKey());
      children.add(paginationKey());
      return new ResolvedStructureKey.Node(
          "SELECT_BLOCK",
          List.of(path.toString(), Boolean.toString(statement.distinct())),
          children);
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
      };
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
      List<ResolvedStructureKey> items = new ArrayList<>(statement.hiddenSelections().size());
      int offset = statement.selections().size();
      for (int index = 0; index < statement.hiddenSelections().size(); index++) {
        HiddenSelection hidden = statement.hiddenSelections().get(index);
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
      List<ResolvedStructureKey> items = new ArrayList<>(statement.orderBy().size());
      for (int index = 0; index < statement.orderBy().size(); index++) {
        OrderByItem item = statement.orderBy().get(index);
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
          statement
              .pagination()
              .map(value -> List.of(value.getClass().getSimpleName()))
              .orElse(List.of()),
          items);
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

  private static <T> LinkedHashSet<T> linkedSet(T value) {
    LinkedHashSet<T> result = new LinkedHashSet<>();
    result.add(value);
    return result;
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

  private record Resolution(
      ResolvedStructureKey key,
      LinkedHashSet<ResolvedColumnIdentity> columns,
      LinkedHashSet<ResolvedParameterIdentity> parameters,
      Nullability effectiveNullability) {

    private Resolution {
      Objects.requireNonNull(key, "key");
      columns = new LinkedHashSet<>(columns);
      parameters = new LinkedHashSet<>(parameters);
      Objects.requireNonNull(effectiveNullability, "effectiveNullability");
    }
  }
}
