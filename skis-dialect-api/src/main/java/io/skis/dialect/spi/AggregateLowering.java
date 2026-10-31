package io.skis.dialect.spi;

import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.ConflictInsertStatement;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DecimalPrecision;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.MergeAction;
import io.skis.sql.ast.MergeStatement;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.QueryClause;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ResolvedExpression;
import io.skis.sql.ast.ResolvedStructureKey;
import io.skis.sql.ast.ReturningStatement;
import io.skis.sql.ast.RowAssignment;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SetAssignment;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Compilation-owned typing of standard expressions, resolved grouping and numeric aggregates. */
final class AggregateLowering {

  private final PortableLowering.AggregateTarget target;
  private final IdentityHashMap<ResolvedStructureKey, QueryBlockAnalysis> analyses =
      new IdentityHashMap<>();
  private final Set<String> qualifiers = new HashSet<>();
  private int aliasOrdinal;

  private AggregateLowering(ResolvedStatement input, PortableLowering.AggregateTarget target) {
    this.target = target;
    QueryBlockAnalysis analysis = input.queryAnalysis();
    if (analysis != null) {
      index(analysis);
    }
    for (ResolvedStatement.SourceOccurrence occurrence : input.sourceOccurrences()) {
      qualifiers.add(occurrence.source().effectiveQualifier().toLowerCase(Locale.ROOT));
    }
  }

  static StatementAst lower(ResolvedStatement input, PortableLowering.AggregateTarget target) {
    QueryBlockAnalysis analysis = input.queryAnalysis();
    if (analysis == null) {
      return mutation(input.statement(), target);
    }
    if (requiresUnLowering(analysis, target)) {
      return input.statement();
    }
    AggregateLowering lowering = new AggregateLowering(input, target);
    if (input.statement() instanceof SelectStatement select) {
      return lowering.select(select, analysis);
    }
    if (input.statement() instanceof CountAst count) {
      return lowering.count(count, analysis);
    }
    return input.statement();
  }

  private static StatementAst mutation(
      StatementAst statement, PortableLowering.AggregateTarget target) {
    return switch (statement) {
      case ReturningStatement returning -> {
        StatementAst lowered = mutation(returning.mutation(), target);
        List<SqlExpression<?>> outputs =
            mutationValues(returning.selections(), target, FromClause.of(returning.target()));
        yield lowered == returning.mutation() && outputs == returning.selections()
            ? returning
            : new ReturningStatement(lowered, outputs, returning.form());
      }
      case ConflictInsertStatement conflict -> {
        var insert = (InsertStatement) mutation(conflict.insert(), target);
        FromClause scope = FromClause.of(conflict.target());
        var assignments = mutationAssignments(conflict.assignments(), target, scope);
        var where = (SqlPredicate) mutationExpression(conflict.updateWhere(), target, false, scope);
        yield insert == conflict.insert()
                && assignments == conflict.assignments()
                && where == conflict.updateWhere()
            ? conflict
            : new ConflictInsertStatement(
                insert, conflict.conflictColumns(), assignments, where, conflict.doNothing());
      }
      case MergeStatement merge -> {
        RelationSource source =
            merge.source() == null ? null : mutationRelation(merge.source(), target);
        // One scope per statement: MergeStatement.scope() allocates a FromClause per call.
        FromClause scope = merge.scope();
        var on = (SqlPredicate) mutationExpression(merge.on(), target, false, scope);
        List<MergeAction> actions = new ArrayList<>(merge.actions().size());
        boolean changed = source != merge.source() || on != merge.on();
        for (var action : merge.actions()) {
          var condition =
              (SqlPredicate) mutationExpression(action.condition(), target, false, scope);
          List<SetAssignment> assignments = new ArrayList<>(action.assignments().size());
          boolean assignmentsChanged = false;
          for (var item : action.assignments()) {
            SetAssignment lowered;
            if (item instanceof UpdateAssignment<?> assignment)
              lowered = assignment(assignment, target, scope);
            else {
              var row = (RowAssignment) item;
              var query = mutationQuery(row.query(), scope, target);
              lowered = query == row.query() ? row : new RowAssignment(row.columns(), query);
            }
            assignmentsChanged |= lowered != item;
            assignments.add(lowered);
          }
          var values = mutationValues(action.values(), target, scope);
          boolean rewritten =
              condition != action.condition() || assignmentsChanged || values != action.values();
          changed |= rewritten;
          actions.add(
              rewritten
                  ? new MergeAction(action.kind(), condition, assignments, action.columns(), values)
                  : action);
        }
        var values = mutationValues(merge.insertValues(), target);
        changed |= values != merge.insertValues();
        yield changed
            ? new MergeStatement(
                merge.target(), source, on, actions, merge.insertColumns(), values, merge.keys())
            : merge;
      }
      case UpdateStatement update -> {
        // One scope per statement: UpdateStatement.scope() allocates a FromClause per call.
        FromClause scope = update.scope();
        List<SetAssignment> assignments = null;
        for (int index = 0; index < update.assignments().size(); index++) {
          SetAssignment original = update.assignments().get(index);
          SetAssignment lowered;
          if (original instanceof UpdateAssignment<?> scalar)
            lowered = assignment(scalar, target, scope);
          else {
            var row = (RowAssignment) original;
            SelectStatement query = mutationQuery(row.query(), scope, target);
            lowered = query == row.query() ? row : new RowAssignment(row.columns(), query);
          }
          if (lowered != original && assignments == null) {
            assignments = new ArrayList<>(update.assignments().size());
            assignments.addAll(update.assignments().subList(0, index));
          }
          if (assignments != null) {
            assignments.add(lowered);
          }
        }
        SqlPredicate where =
            (SqlPredicate) mutationExpression(update.where(), target, false, scope);
        FromClause from = mutationSources(update.from(), target);
        yield assignments == null && where == update.where() && from == update.from()
            ? update
            : new UpdateStatement(
                update.target(),
                assignments == null ? update.assignments() : assignments,
                where,
                from);
      }
      case DeleteStatement delete -> {
        SqlPredicate where =
            (SqlPredicate) mutationExpression(delete.where(), target, false, delete.scope());
        FromClause using = mutationSources(delete.using(), target);
        yield where == delete.where() && using == delete.using()
            ? delete
            : new DeleteStatement(delete.target(), where, using);
      }
      case InsertStatement insert -> {
        SelectStatement query =
            insert.query() == null
                ? null
                : (SelectStatement) lower(ResolvedStatement.nestedQuery(insert.query()), target);
        List<SqlExpression<?>> values = mutationValues(insert.values(), target);
        List<List<SqlExpression<?>>> additional = new ArrayList<>(insert.additionalRows().size());
        boolean changed = query != insert.query() || values != insert.values();
        for (var row : insert.additionalRows()) {
          var lowered = mutationValues(row, target);
          changed |= lowered != row;
          additional.add(lowered);
        }
        yield changed
            ? new InsertStatement(insert.target(), insert.columns(), values, additional, query)
            : insert;
      }
      // Other mutation kinds are handled independently.
      default -> statement;
    };
  }

  private static SelectStatement mutationQuery(
      SelectStatement query, FromClause outer, PortableLowering.AggregateTarget target) {
    ResolvedStatement input = ResolvedStatement.mutationQuery(query, outer);
    QueryBlockAnalysis analysis = Objects.requireNonNull(input.queryAnalysis());
    if (requiresUnLowering(analysis, target)) return query;
    AggregateLowering lowering = new AggregateLowering(input, target);
    for (var source : outer.occurrences())
      lowering.qualifiers.add(source.effectiveQualifier().toLowerCase(Locale.ROOT));
    return lowering.select(query, analysis);
  }

  private static @Nullable FromClause mutationSources(
      @Nullable FromClause sources, PortableLowering.AggregateTarget target) {
    if (sources == null) return null;
    RelationSource root = mutationRelation(sources.root(), target);
    List<JoinClause> joins = new ArrayList<>(sources.joins().size());
    boolean changed = root != sources.root();
    for (JoinClause join : sources.joins()) {
      List<JoinClause> visible = new ArrayList<>(sources.joins().subList(0, joins.size()));
      visible.add(new JoinClause(JoinType.CROSS, join.right(), null));
      FromClause onScope = new FromClause(sources.root(), visible);
      RelationSource right = mutationRelation(join.right(), target);
      SqlPredicate original = join.on().orElse(null);
      SqlPredicate on =
          original == null
              ? null
              : (SqlPredicate) mutationExpression(original, target, false, onScope);
      changed |= right != join.right() || on != original;
      joins.add(
          right == join.right() && on == original ? join : new JoinClause(join.type(), right, on));
    }
    return changed ? new FromClause(root, joins) : sources;
  }

  private static RelationSource mutationRelation(
      RelationSource source, PortableLowering.AggregateTarget target) {
    if (!(source
        instanceof
        DerivedRelationSource(SelectStatement statement, DerivedRelationReference reference)))
      return source;
    SelectStatement lowered =
        (SelectStatement) lower(ResolvedStatement.nestedQuery(statement), target);
    return lowered == statement ? source : new DerivedRelationSource(lowered, reference);
  }

  private static List<UpdateAssignment<?>> mutationAssignments(
      List<UpdateAssignment<?>> original,
      PortableLowering.AggregateTarget target,
      FromClause outer) {
    List<UpdateAssignment<?>> changed = null;
    for (int i = 0; i < original.size(); i++) {
      var before = original.get(i);
      var after = assignment(before, target, outer);
      if (after != before && changed == null) {
        changed = new ArrayList<>(original.size());
        changed.addAll(original.subList(0, i));
      }
      if (changed != null) changed.add(after);
    }
    return changed == null ? original : List.copyOf(changed);
  }

  @SuppressWarnings("unchecked")
  private static List<SqlExpression<?>> unchangedValues(List<? extends SqlExpression<?>> values) {
    return (List<SqlExpression<?>>) values;
  }

  private static List<SqlExpression<?>> mutationValues(
      List<? extends SqlExpression<?>> original, PortableLowering.AggregateTarget target) {
    return mutationValues(original, target, null);
  }

  private static List<SqlExpression<?>> mutationValues(
      List<? extends SqlExpression<?>> original,
      PortableLowering.AggregateTarget target,
      @Nullable FromClause outer) {
    List<SqlExpression<?>> changed = null;
    for (int index = 0; index < original.size(); index++) {
      SqlExpression<?> before = original.get(index);
      SqlExpression<?> after = mutationExpression(before, target, false, outer);
      if (after != before && changed == null) {
        changed = new ArrayList<>(original.size());
        changed.addAll(original.subList(0, index));
      }
      if (changed != null) changed.add(after);
    }
    return changed == null ? unchangedValues(original) : List.copyOf(changed);
  }

  private static <T> UpdateAssignment<T> assignment(
      UpdateAssignment<T> assignment,
      PortableLowering.AggregateTarget target,
      @Nullable FromClause outer) {
    SqlExpression<?> value = mutationExpression(assignment.value(), target, false, outer);
    if (value == assignment.value()) {
      return assignment;
    }
    @SuppressWarnings("unchecked")
    SqlExpression<T> typed = (SqlExpression<T>) value;
    return new UpdateAssignment<>(assignment.column(), typed);
  }

  private static SqlExpression<?> mutationExpression(
      SqlExpression<?> original,
      PortableLowering.AggregateTarget target,
      boolean typeValue,
      @Nullable FromClause outer) {
    List<? extends SqlExpression<?>> operands = AstExpressions.operands(original);
    boolean typeOperands = requiresTypedOperands(original, operands);
    List<SqlExpression<?>> rewritten = null;
    for (int index = 0; index < operands.size(); index++) {
      SqlExpression<?> operand = operands.get(index);
      SqlExpression<?> lowered = mutationExpression(operand, target, typeOperands, outer);
      if (lowered != operand && rewritten == null) {
        rewritten = new ArrayList<>(operands.size());
        rewritten.addAll(operands.subList(0, index));
      }
      if (rewritten != null) {
        rewritten.add(lowered);
      }
    }
    SelectStatement nested = AstExpressions.subquery(original);
    SelectStatement loweredQuery =
        nested == null
            ? null
            : outer == null
                ? (SelectStatement) lower(ResolvedStatement.nestedQuery(nested), target)
                : mutationQuery(nested, outer, target);
    SqlExpression<?> result =
        rewritten == null && loweredQuery == nested
            ? original
            : AstExpressions.rebuild(
                original, operands, rewritten == null ? operands : rewritten, loweredQuery);
    return typedValue(result, target, typeValue, false);
  }

  private static boolean requiresUnLowering(
      QueryBlockAnalysis analysis, PortableLowering.AggregateTarget target) {
    return !analysis.requiresStandardExpressionLowering()
        && !switch (target) {
          case POSTGRESQL -> analysis.hasRealSum();
          case H2 -> analysis.hasSmallIntegerAverage() || analysis.hasDoubleAggregate();
        };
  }

  private void index(QueryBlockAnalysis analysis) {
    analyses.put(analysis.structureKey(), analysis);
    for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
      index(nested.analysis());
    }
  }

  private SelectStatement select(SelectStatement select, QueryBlockAnalysis analysis) {
    if (requiresUnLowering(analysis, target)) {
      return select;
    }
    Block block = new Block(select, analysis);
    List<SqlExpression<?>> selections = new ArrayList<>(select.selections().size());
    for (int index = 0; index < select.selections().size(); index++) {
      selections.add(block.root(select.selections().get(index), QueryClause.SELECT, index));
    }
    List<HiddenSelection> hidden = new ArrayList<>(select.hiddenSelections().size());
    for (int index = 0; index < select.hiddenSelections().size(); index++) {
      HiddenSelection item = select.hiddenSelections().get(index);
      hidden.add(
          new HiddenSelection(
              block.root(item.expression(), QueryClause.SELECT, selections.size() + index),
              item.alias()));
    }
    FromClause from = block.from(select.fromClause());
    SqlPredicate where =
        select
            .where()
            .map(value -> (SqlPredicate) block.root(value, QueryClause.WHERE, 0))
            .orElse(null);
    List<SqlExpression<?>> groups = new ArrayList<>(select.groupBy().size());
    Set<CastExpression<?>> typedValueGroups = new HashSet<>();
    for (int index = 0; index < select.groupBy().size(); index++) {
      SqlExpression<?> group = block.root(select.groupBy().get(index), QueryClause.GROUP_BY, index);
      // A required typed value cast may coincide with an explicit cast already declared by
      // the caller. Keep the original declaration intact and emit the identical physical key once.
      if (!(group instanceof CastExpression<?> cast)
          || !(cast.operand() instanceof ParameterSlot<?>
              || cast.operand() instanceof LiteralExpression<?>)
          || typedValueGroups.add(cast)) {
        groups.add(group);
      }
    }
    SqlPredicate having =
        select
            .having()
            .map(value -> (SqlPredicate) block.root(value, QueryClause.HAVING, 0))
            .orElse(null);
    List<OrderByItem> ordering = new ArrayList<>(select.orderBy().size());
    for (int index = 0; index < select.orderBy().size(); index++) {
      OrderByItem item = select.orderBy().get(index);
      ordering.add(
          new OrderByItem(
              block.root(item.expression(), QueryClause.ORDER_BY, index),
              item.direction(),
              item.nullOrder()));
    }
    if (from == select.fromClause()
        && sameExpressions(select.selections(), selections)
        && select.hiddenSelections().equals(hidden)
        && where == select.where().orElse(null)
        && sameExpressions(select.groupBy(), groups)
        && having == select.having().orElse(null)
        && select.orderBy().equals(ordering)) {
      return select;
    }
    return new SelectStatement(
        select.distinct(),
        selections,
        hidden,
        from,
        where,
        groups,
        having,
        ordering,
        select.pagination().orElse(null));
  }

  private CountAst count(CountAst count, QueryBlockAnalysis analysis) {
    Block block = new Block(null, analysis);
    FromClause from = block.from(count.fromClause());
    SqlPredicate where =
        count
            .predicate()
            .map(value -> (SqlPredicate) block.root(value, QueryClause.WHERE, 0))
            .orElse(null);
    SqlExpression<?> distinct =
        count
            .distinctExpression()
            .map(value -> block.root(value, QueryClause.SELECT, 0))
            .orElse(null);
    return from == count.fromClause()
            && where == count.predicate().orElse(null)
            && distinct == count.distinctExpression().orElse(null)
        ? count
        : new CountAst(from, where, distinct, count.nullCompensated());
  }

  private final class Block {
    private final Map<Site, ResolvedExpression> roots = new HashMap<>();
    private final Map<Integer, QueryBlockAnalysis> derived = new HashMap<>();
    private final KeyMatches groups = new KeyMatches();
    private final KeyMatches parameterizedGroups = new KeyMatches();
    private final Map<Integer, Integer> parameterColumns = new LinkedHashMap<>();
    private final @Nullable ParameterRelationSource parameterRelation;

    private Block(@Nullable SelectStatement select, QueryBlockAnalysis analysis) {
      for (ResolvedExpression resolved : analysis.expressions()) {
        roots.put(
            new Site(resolved.position().clause(), resolved.position().itemOrdinal()), resolved);
      }
      for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
        if (nested.kind() == QueryBlockAnalysis.NestedQueryKind.DERIVED_TABLE) {
          derived.put(nested.location().itemOrdinal(), nested.analysis());
        }
      }
      LinkedHashMap<Integer, ParameterSlot<?>> slots = new LinkedHashMap<>();
      if (select != null) {
        for (int index = 0; index < select.groupBy().size(); index++) {
          ResolvedExpression resolved = requireRoot(QueryClause.GROUP_BY, index);
          groups.add(resolved.structureKey());
          boolean localRows =
              resolved.columnDependencies().stream()
                  .anyMatch(column -> column.source().blockPath().equals(analysis.path()));
          if (localRows && !resolved.parameterDependencies().isEmpty()) {
            parameterizedGroups.add(resolved.structureKey());
            collectParameters(select.groupBy().get(index), slots);
          }
        }
      }
      if (slots.isEmpty()) {
        parameterRelation = null;
      } else {
        List<DerivedOutputColumn> outputs = new ArrayList<>(slots.size());
        for (ParameterSlot<?> slot : slots.values()) {
          int ordinal = outputs.size();
          parameterColumns.put(slot.ordinal(), ordinal);
          outputs.add(
              new DerivedOutputColumn(
                  Identifier.of("p" + ordinal),
                  slot.javaType(),
                  slot.sqlType(),
                  slot.nullability()));
        }
        parameterRelation =
            new ParameterRelationSource(
                List.copyOf(slots.values()), new DerivedRelationReference(nextAlias(), outputs));
      }
    }

    private ResolvedExpression requireRoot(QueryClause clause, int ordinal) {
      return Objects.requireNonNull(
          roots.get(new Site(clause, ordinal)), "resolved clause expression");
    }

    private SqlExpression<?> root(SqlExpression<?> expression, QueryClause clause, int ordinal) {
      return expression(
          expression,
          requireRoot(clause, ordinal).structureKey(),
          clause != QueryClause.JOIN_ON,
          false,
          true);
    }

    private FromClause from(FromClause original) {
      RelationSource root = relation(original.root(), 0);
      List<JoinClause> joins =
          new ArrayList<>(original.joins().size() + (parameterRelation == null ? 0 : 1));
      boolean changed = root != original.root();
      for (int index = 0; index < original.joins().size(); index++) {
        JoinClause join = original.joins().get(index);
        RelationSource right = relation(join.right(), index + 1);
        SqlPredicate on =
            join.on().isEmpty()
                ? null
                : (SqlPredicate) root(join.on().orElseThrow(), QueryClause.JOIN_ON, index + 1);
        changed |= right != join.right() || on != join.on().orElse(null);
        joins.add(
            right == join.right() && on == join.on().orElse(null)
                ? join
                : new JoinClause(join.type(), right, on));
      }
      if (parameterRelation != null) {
        joins.add(new JoinClause(JoinType.CROSS, parameterRelation, null));
        changed = true;
      }
      return changed ? new FromClause(root, joins) : original;
    }

    private RelationSource relation(RelationSource source, int ordinal) {
      if (!(source instanceof DerivedRelationSource relation)) {
        return source;
      }
      SelectStatement lowered =
          select(
              relation.statement(),
              Objects.requireNonNull(derived.get(ordinal), "derived analysis"));
      return lowered == relation.statement()
          ? relation
          : new DerivedRelationSource(lowered, relation.reference());
    }

    private SqlExpression<?> expression(
        SqlExpression<?> original,
        ResolvedStructureKey key,
        boolean matchGroups,
        boolean replaceParameters,
        boolean typeValue) {
      boolean matching = matchGroups && groups.contains(key);
      boolean replace = replaceParameters || (matching && parameterizedGroups.contains(key));
      if (replace && original instanceof ParameterSlot<?> slot) {
        ParameterRelationSource relation =
            Objects.requireNonNull(parameterRelation, "group parameter relation");
        return new DerivedColumnExpression<>(
            relation.reference(),
            Objects.requireNonNull(parameterColumns.get(slot.ordinal()), "parameter column"));
      }
      List<? extends SqlExpression<?>> operands = AstExpressions.operands(original);
      List<ResolvedStructureKey> keys =
          key instanceof ResolvedStructureKey.Node node ? node.children() : List.of();
      List<SqlExpression<?>> rewritten = null;
      boolean aggregateInput = original instanceof AggregateExpression<?>;
      boolean typeOperands = requiresTypedOperands(original, operands);
      for (int index = 0; index < operands.size(); index++) {
        SqlExpression<?> operand = operands.get(index);
        SqlExpression<?> replacement =
            expression(
                operand,
                keys.get(index),
                matchGroups && !aggregateInput && !matching,
                replace,
                typeOperands);
        if (replacement != operand && rewritten == null) {
          rewritten = new ArrayList<>(operands.size());
          rewritten.addAll(operands.subList(0, index));
        }
        if (rewritten != null) {
          rewritten.add(replacement);
        }
      }
      SelectStatement subquery = AstExpressions.subquery(original);
      if (subquery != null) {
        QueryBlockAnalysis nested =
            Objects.requireNonNull(analyses.get(keys.getLast()), "nested query analysis");
        subquery = select(subquery, nested);
      }
      SqlExpression<?> result =
          rewritten == null && subquery == AstExpressions.subquery(original)
              ? original
              : AstExpressions.rebuild(
                  original, operands, rewritten == null ? operands : rewritten, subquery);
      if (result instanceof AggregateExpression<?> aggregate) {
        result = numeric(aggregate);
      }
      return typedValue(result, target, typeValue, matching);
    }
  }

  private static SqlExpression<?> typedValue(
      SqlExpression<?> expression,
      PortableLowering.AggregateTarget target,
      boolean typeValue,
      boolean matching) {
    boolean unboundedDecimal =
        target == PortableLowering.AggregateTarget.H2
            && expression instanceof ParameterSlot<?> parameter
            && (parameter.javaType() == BigDecimal.class
                || parameter.javaType() == BigInteger.class);
    boolean nativeLiteral =
        expression instanceof LiteralExpression<?> literal
            && literal.kind() != LiteralExpression.Kind.NULL
            && (literal.sqlType() == SqlType.INTEGER || literal.sqlType() == SqlType.BOOLEAN);
    return (typeValue || unboundedDecimal)
            && (expression instanceof ParameterSlot<?>
                || expression instanceof LiteralExpression<?>)
            && (!nativeLiteral || matching)
        ? new CastExpression<>(expression, expression.javaType(), expression.sqlType())
        : expression;
  }

  private static boolean requiresTypedOperands(
      SqlExpression<?> expression, List<? extends SqlExpression<?>> operands) {
    if (expression instanceof CastExpression<?> || expression instanceof InSubqueryPredicate<?>) {
      return false;
    }
    if (expression instanceof ConcatExpression) {
      return false;
    }
    //noinspection DeconstructionCanBeUsed
    if (expression instanceof CaseExpression<?> conditional) {
      for (var branch : conditional.branches()) {
        if (inferableValue(branch.result())) {
          return false;
        }
      }
      var otherwise = conditional.otherwise();
      return otherwise.isEmpty() || !inferableValue(otherwise.orElseThrow());
    }
    if (expression instanceof ComparisonPredicate<?>
        || expression instanceof BetweenPredicate<?>
        || expression instanceof LikePredicate
        || expression instanceof InPredicate<?>
        || expression instanceof ArithmeticExpression<?>
        || expression instanceof CoalesceExpression<?>) {
      for (SqlExpression<?> operand : operands) {
        if (inferableValue(operand)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean inferableValue(SqlExpression<?> expression) {
    if (expression instanceof ParameterSlot<?>) {
      return false;
    }
    if (expression instanceof LiteralExpression<?> literal) {
      return literal.kind() != LiteralExpression.Kind.NULL
          && (literal.sqlType() == SqlType.INTEGER || literal.sqlType() == SqlType.BOOLEAN);
    }
    return true;
  }

  private SqlExpression<?> numeric(AggregateExpression<?> aggregate) {
    SqlExpression<?> operand = aggregate.operand().orElse(null);
    if (operand == null) {
      return aggregate;
    }
    if (target == PortableLowering.AggregateTarget.POSTGRESQL
        && aggregate.kind() == AggregateExpression.Kind.SUM
        && operand.sqlType() == SqlType.REAL) {
      return aggregate.withOperand(new CastExpression<>(operand, Double.class, SqlType.DOUBLE));
    }
    if (target == PortableLowering.AggregateTarget.H2) {
      if (aggregate.kind() == AggregateExpression.Kind.AVG) {
        int precision =
            switch (operand.sqlType()) {
              case TINYINT -> 3;
              case SMALLINT -> 5;
              case INTEGER -> 10;
              default -> 0;
            };
        if (precision != 0) {
          return aggregate.withOperand(
              new CastExpression<>(
                  operand, BigDecimal.class, SqlType.DECIMAL, new DecimalPrecision(precision, 0)));
        }
      }
      if ((aggregate.kind() == AggregateExpression.Kind.SUM
              || aggregate.kind() == AggregateExpression.Kind.AVG)
          && operand.sqlType() == SqlType.DOUBLE) {
        return new CastExpression<>(aggregate, Double.class, SqlType.DOUBLE);
      }
    }
    return aggregate;
  }

  private Identifier nextAlias() {
    String alias;
    do {
      alias = "_skis_gp" + aliasOrdinal++;
    } while (!qualifiers.add(alias.toLowerCase(Locale.ROOT)));
    return Identifier.of(alias);
  }

  private static void collectParameters(
      SqlExpression<?> expression, Map<Integer, ParameterSlot<?>> slots) {
    if (expression instanceof ParameterSlot<?> slot) {
      slots.putIfAbsent(slot.ordinal(), slot);
    }
    for (SqlExpression<?> operand : AstExpressions.operands(expression)) {
      collectParameters(operand, slots);
    }
  }

  private static boolean sameExpressions(
      List<? extends SqlExpression<?>> first, List<? extends SqlExpression<?>> second) {
    if (first.size() != second.size()) {
      return false;
    }
    for (int index = 0; index < first.size(); index++) {
      if (first.get(index) != second.get(index)) {
        return false;
      }
    }
    return true;
  }

  private record Site(QueryClause clause, int ordinal) {}

  /** Hashes each resolved subtree once; long arithmetic chains do not incur quadratic hashing. */
  private static final class KeyMatches {
    private final IdentityHashMap<ResolvedStructureKey, Integer> hashes = new IdentityHashMap<>();
    private final Map<Integer, List<ResolvedStructureKey>> keys = new HashMap<>();

    private void add(ResolvedStructureKey key) {
      keys.computeIfAbsent(hash(key), ignored -> new ArrayList<>()).add(key);
    }

    private boolean contains(ResolvedStructureKey key) {
      if (keys.isEmpty()) {
        return false;
      }
      List<ResolvedStructureKey> candidates = keys.get(hash(key));
      return candidates != null && candidates.contains(key);
    }

    private int hash(ResolvedStructureKey key) {
      Integer cached = hashes.get(key);
      if (cached != null) {
        return cached;
      }
      int hash =
          switch (key) {
            case ResolvedStructureKey.Atom atom ->
                31 * atom.kind().hashCode() + atom.attributes().hashCode();
            case ResolvedStructureKey.Node node -> {
              int childrenHash = 1;
              for (ResolvedStructureKey child : node.children()) {
                childrenHash = 31 * childrenHash + hash(child);
              }
              yield 31 * (31 * node.kind().hashCode() + node.attributes().hashCode())
                  + childrenHash;
            }
          };
      hashes.put(key, hash);
      return hash;
    }
  }
}
