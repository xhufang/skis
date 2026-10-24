package io.skis.dialect.spi;

import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.Limit;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectPagination;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Portable structural normalization applied to every final statement before post-validation and
 * final parameter layout.
 *
 * <p>The normalizer owns the four framework behaviors that previously lived in renderers: NULL
 * ordering fallback, nullable DISTINCT count compensation, selected-output references for DISTINCT
 * scalar ordering, and deterministic empty {@code IN}/{@code NOT IN}. It only rebuilds AST nodes
 * and never reads a parameter value or emits SQL text. Returning the exact input instance means
 * that no portable conversion was needed.
 */
public final class PortableLowering {

  private PortableLowering() {}

  /** Applies portable normalization for the target capability set. */
  public static StatementAst normalize(StatementAst statement, DialectCapabilities capabilities) {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(capabilities, "capabilities");
    return new Normalizer(capabilities).statement(statement);
  }

  private record Normalizer(boolean nativeNullOrdering) {

    private Normalizer(DialectCapabilities nativeNullOrdering) {
      this(nativeNullOrdering.supports(DialectFeature.NULLS_FIRST_LAST));
    }

    private StatementAst statement(StatementAst statement) {
      return switch (statement) {
        case SelectStatement select -> select(select);
        case CountAst count -> count(count);
        case InsertStatement insert -> insert(insert);
        case UpdateStatement update -> update(update);
        case DeleteStatement delete -> delete(delete);
        default ->
            throw new IllegalArgumentException(
                "portable normalization does not support statement node "
                    + statement.getClass().getName());
      };
    }

    private SelectStatement select(SelectStatement select) {
      List<SqlExpression<?>> originalSelections = select.selections();
      List<SqlExpression<?>> selections = mapIfChanged(originalSelections, this::expression);
      List<HiddenSelection> originalHidden = select.hiddenSelections();
      List<HiddenSelection> hidden =
          mapIfChanged(
              originalHidden,
              item -> {
                SqlExpression<?> original = item.expression();
                SqlExpression<?> normalized = expression(original);

                if (normalized == original) {
                  return item;
                }

                return new HiddenSelection(normalized, item.alias());
              });
      FromClause originalFrom = select.fromClause();
      FromClause fromClause = fromClause(originalFrom);
      SqlPredicate originalWhere = select.where().orElse(null);
      SqlPredicate where = originalWhere == null ? null : predicate(originalWhere);
      List<SqlExpression<?>> originalGroupBy = select.groupBy();
      List<SqlExpression<?>> groupBy = mapIfChanged(originalGroupBy, this::expression);
      SqlPredicate originalHaving = select.having().orElse(null);
      SqlPredicate having = originalHaving == null ? null : predicate(originalHaving);
      List<OrderByItem> originalOrderBy = select.orderBy();
      List<OrderByItem> rewrittenOrderBy = null;
      boolean distinct = select.distinct();
      int index = 0;
      for (OrderByItem item : originalOrderBy) {
        SqlExpression<?> originalExpression = item.expression();
        SqlExpression<?> normalized = expression(originalExpression);
        NullOrder originalNullOrder = item.nullOrder();
        NullOrder nullOrder = originalNullOrder;
        boolean emulateNullOrdering = nullOrder != NullOrder.DIALECT_DEFAULT && !nativeNullOrdering;
        SqlExpression<?> nullRankOperand = null;
        if (distinct
            && !(normalized instanceof ColumnExpression<?, ?>)
            && !(normalized instanceof DerivedColumnExpression<?>)
            && !(normalized instanceof SelectedOutputReference<?>)) {
          int selected = selectedIndex(selections, normalized);
          if (selected >= 0) {
            Nullability nullability = fromClause.effectiveNullability(normalized);
            if (emulateNullOrdering && !nullability.isNullable()) {
              nullOrder = NullOrder.DIALECT_DEFAULT;
              emulateNullOrdering = false;
            }
            if (emulateNullOrdering && !(normalized instanceof ScalarSubqueryExpression<?>)) {
              nullRankOperand = normalized;
            }
            normalized =
                new SelectedOutputReference<>(
                    selected + 1, normalized.javaType(), normalized.sqlType(), nullability);
          } else if (normalized instanceof ScalarSubqueryExpression<?>) {
            throw new IllegalArgumentException(
                "DISTINCT scalar ORDER BY must reference a selected expression");
          }
        }
        if (emulateNullOrdering) {
          if (normalized instanceof SelectedOutputReference<?> && nullRankOperand == null) {
            throw new IllegalArgumentException(
                "DISTINCT selected output ordering requires native NULLS FIRST/LAST support");
          }
          if (rewrittenOrderBy == null) {
            rewrittenOrderBy = new ArrayList<>(originalOrderBy.size() + 1);
            rewrittenOrderBy.addAll(originalOrderBy.subList(0, index));
          }
          SqlExpression<?> rankExpression = nullRankOperand == null ? normalized : nullRankOperand;
          rewrittenOrderBy.add(
              new OrderByItem(
                  nullRank(rankExpression, nullOrder),
                  OrderDirection.ASC,
                  NullOrder.DIALECT_DEFAULT));
          rewrittenOrderBy.add(
              new OrderByItem(normalized, item.direction(), NullOrder.DIALECT_DEFAULT));
        } else {
          boolean itemChanged = normalized != originalExpression || nullOrder != originalNullOrder;
          OrderByItem result =
              itemChanged ? new OrderByItem(normalized, item.direction(), nullOrder) : item;
          if (itemChanged && rewrittenOrderBy == null) {
            rewrittenOrderBy = new ArrayList<>(originalOrderBy.size());
            rewrittenOrderBy.addAll(originalOrderBy.subList(0, index));
          }
          if (rewrittenOrderBy != null) {
            rewrittenOrderBy.add(result);
          }
        }
        index++;
      }
      List<OrderByItem> orderBy = rewrittenOrderBy == null ? originalOrderBy : rewrittenOrderBy;
      SelectPagination originalPagination = select.pagination().orElse(null);
      SelectPagination pagination = pagination(originalPagination);
      boolean changed =
          selections != originalSelections
              || hidden != originalHidden
              || fromClause != originalFrom
              || where != originalWhere
              || groupBy != originalGroupBy
              || having != originalHaving
              || orderBy != originalOrderBy
              || pagination != originalPagination;
      if (!changed) {
        return select;
      }
      return new SelectStatement(
          distinct, selections, hidden, fromClause, where, groupBy, having, orderBy, pagination);
    }

    private CountAst count(CountAst count) {
      boolean changed = false;
      FromClause fromClause = fromClause(count.fromClause());
      changed |= fromClause != count.fromClause();
      SqlPredicate predicate = count.predicate().map(this::predicate).orElse(null);
      changed |= predicate != count.predicate().orElse(null);
      SqlExpression<?> distinct = count.distinctExpression().map(this::expression).orElse(null);
      changed |= distinct != count.distinctExpression().orElse(null);

      boolean compensate =
          distinct != null
              && (count.nullCompensated()
                  || fromClause.effectiveNullability(distinct).isNullable());
      changed |= compensate != count.nullCompensated();
      if (!changed) {
        return count;
      }
      return new CountAst(fromClause, predicate, distinct, compensate);
    }

    private InsertStatement insert(InsertStatement insert) {
      boolean changed = false;
      List<SqlExpression<?>> values = new ArrayList<>(insert.values().size());
      for (SqlExpression<?> value : insert.values()) {
        SqlExpression<?> normalized = expression(value);
        changed |= normalized != value;
        values.add(normalized);
      }
      return changed ? new InsertStatement(insert.target(), insert.columns(), values) : insert;
    }

    private UpdateStatement update(UpdateStatement update) {
      boolean changed = false;
      List<UpdateAssignment<?>> assignments = new ArrayList<>(update.assignments().size());
      for (UpdateAssignment<?> assignment : update.assignments()) {
        UpdateAssignment<?> normalized = updateAssignment(assignment);
        changed |= normalized != assignment;
        assignments.add(normalized);
      }
      SqlPredicate where = predicate(update.where());
      changed |= where != update.where();
      return changed ? new UpdateStatement(update.target(), assignments, where) : update;
    }

    private DeleteStatement delete(DeleteStatement delete) {
      SqlPredicate where = predicate(delete.where());
      return where == delete.where() ? delete : new DeleteStatement(delete.target(), where);
    }

    private <T> UpdateAssignment<T> updateAssignment(UpdateAssignment<T> assignment) {
      SqlExpression<T> normalized = unchecked(expression(assignment.value()));
      return normalized == assignment.value()
          ? assignment
          : new UpdateAssignment<>(assignment.column(), normalized);
    }

    private <T> CoalesceExpression<T> coalesce(CoalesceExpression<T> coalesce) {
      boolean changed = false;
      List<SqlExpression<T>> operands = new ArrayList<>(coalesce.operands().size());
      for (SqlExpression<T> operand : coalesce.operands()) {
        SqlExpression<T> normalized = unchecked(expression(operand));
        changed |= normalized != operand;
        operands.add(normalized);
      }
      return changed ? new CoalesceExpression<>(operands) : coalesce;
    }

    private FromClause fromClause(FromClause fromClause) {
      boolean changed = false;
      RelationSource root = relation(fromClause.root());
      changed |= root != fromClause.root();
      List<JoinClause> joins = new ArrayList<>(fromClause.joins().size());
      for (JoinClause join : fromClause.joins()) {
        RelationSource right = relation(join.right());
        SqlPredicate on = join.on().map(this::predicate).orElse(null);
        boolean joinChanged = right != join.right() || on != join.on().orElse(null);
        changed |= joinChanged;
        joins.add(joinChanged ? new JoinClause(join.type(), right, on) : join);
      }
      return changed ? new FromClause(root, joins) : fromClause;
    }

    private RelationSource relation(RelationSource source) {
      if (source instanceof DerivedRelationSource derived) {
        SelectStatement normalized = select(derived.statement());
        if (normalized == derived.statement()) {
          return derived;
        }
        return new DerivedRelationSource(normalized, derived.reference());
      }
      return source;
    }

    private @Nullable SelectPagination pagination(@Nullable SelectPagination pagination) {
      if (pagination == null) {
        return null;
      }
      return switch (pagination) {
        case KeysetSeek keyset -> {
          SqlPredicate predicate = predicate(keyset.predicate());
          yield predicate == keyset.predicate()
              ? keyset
              : new KeysetSeek(predicate, keyset.limit());
        }
        case Limit limit -> limit;
        case OffsetLimit offset -> offset;
      };
    }

    private SqlExpression<?> expression(SqlExpression<?> expression) {
      return switch (expression) {
        case ParameterSlot<?> parameter -> parameter;
        case BooleanPredicate predicate -> predicate;
        case SelectedOutputReference<?> reference -> reference;
        case ColumnExpression<?, ?> column -> column;
        case DerivedColumnExpression<?> column -> column;
        case LiteralExpression<?> literal -> literal;
        case ArithmeticExpression<?> arithmetic -> {
          SqlExpression<?> originalLeft = arithmetic.left();
          SqlExpression<?> originalRight = arithmetic.right();
          SqlExpression<?> left = expression(originalLeft);
          SqlExpression<?> right = expression(originalRight);
          yield left == originalLeft && right == originalRight
              ? arithmetic
              : new ArithmeticExpression<>(
                  unchecked(left), arithmetic.operator(), unchecked(right));
        }
        case ConcatExpression concat -> {
          List<SqlExpression<String>> originalOperands = concat.operands();
          List<SqlExpression<String>> operands =
              mapIfChanged(
                  originalOperands,
                  operand -> {
                    SqlExpression<?> normalized = expression(operand);
                    return normalized == operand ? operand : stringExpression(normalized);
                  });
          yield operands == originalOperands ? concat : new ConcatExpression(operands);
        }
        case CaseExpression<?> caseExpression -> {
          List<? extends CaseWhen<?>> originalBranches = caseExpression.branches();
          List<CaseWhen<?>> branches = null;
          int index = 0;
          for (CaseWhen<?> branch : originalBranches) {
            SqlPredicate originalCondition = branch.condition();
            SqlExpression<?> originalResult = branch.result();
            SqlPredicate condition = predicate(originalCondition);
            SqlExpression<?> result = expression(originalResult);
            boolean branchChanged = condition != originalCondition || result != originalResult;
            if (branches == null && branchChanged) {
              branches = new ArrayList<>(originalBranches.size());
              for (int i = 0; i < index; i++) {
                branches.add(originalBranches.get(i));
              }
            }
            if (branches != null) {
              branches.add(branchChanged ? new CaseWhen<>(condition, unchecked(result)) : branch);
            }
            index++;
          }
          SqlExpression<?> originalOtherwise = caseExpression.otherwise().orElse(null);
          SqlExpression<?> otherwise =
              originalOtherwise == null ? null : expression(originalOtherwise);
          if (branches == null && otherwise == originalOtherwise) {
            yield caseExpression;
          }
          if (branches == null) {
            branches = new ArrayList<>(originalBranches);
          }
          yield caseExpression(branches, otherwise);
        }
        case CastExpression<?> cast -> {
          SqlExpression<?> originalOperand = cast.operand();
          SqlExpression<?> operand = expression(originalOperand);
          yield operand == originalOperand
              ? cast
              : new CastExpression<>(operand, cast.javaType(), cast.sqlType());
        }
        case CoalesceExpression<?> coalesce -> coalesce(coalesce);
        case ComparisonPredicate<?> comparison -> {
          SqlExpression<?> originalLeft = comparison.left();
          SqlExpression<?> originalRight = comparison.right();
          SqlExpression<?> left = expression(originalLeft);
          SqlExpression<?> right = expression(originalRight);
          yield left == originalLeft && right == originalRight
              ? comparison
              : new ComparisonPredicate<>(unchecked(left), comparison.operator(), unchecked(right));
        }
        case LogicalPredicate logical -> {
          List<SqlPredicate> originalOperands = logical.operands();
          List<SqlPredicate> operands = mapIfChanged(originalOperands, this::predicate);
          yield operands == originalOperands
              ? logical
              : new LogicalPredicate(logical.operator(), operands);
        }
        case NullPredicate nullPredicate -> {
          SqlExpression<?> originalOperand = nullPredicate.operand();
          SqlExpression<?> operand = expression(originalOperand);
          yield operand == originalOperand
              ? nullPredicate
              : new NullPredicate(operand, nullPredicate.operator());
        }
        case BetweenPredicate<?> between -> {
          SqlExpression<?> originalValue = between.value();
          SqlExpression<?> originalLower = between.lower();
          SqlExpression<?> originalUpper = between.upper();
          SqlExpression<?> value = expression(originalValue);
          SqlExpression<?> lower = expression(originalLower);
          SqlExpression<?> upper = expression(originalUpper);
          yield value == originalValue && lower == originalLower && upper == originalUpper
              ? between
              : new BetweenPredicate<>(unchecked(value), unchecked(lower), unchecked(upper));
        }
        case LikePredicate like -> {
          SqlExpression<?> originalValue = like.value();
          SqlExpression<?> originalPattern = like.pattern();
          SqlExpression<?> value = expression(originalValue);
          SqlExpression<?> pattern = expression(originalPattern);
          yield value == originalValue && pattern == originalPattern
              ? like
              : new LikePredicate(value, pattern);
        }
        case InPredicate<?> in -> in(in);
        case InSubqueryPredicate<?> in -> {
          SqlExpression<?> originalValue = in.value();
          SelectStatement originalSubquery = in.subquery();
          SqlExpression<?> value = expression(originalValue);
          SelectStatement subquery = select(originalSubquery);
          yield value == originalValue && subquery == originalSubquery
              ? in
              : new InSubqueryPredicate<>(unchecked(value), subquery, in.negated());
        }
        case ExistsPredicate exists -> {
          SelectStatement originalSubquery = exists.subquery();
          SelectStatement subquery = select(originalSubquery);
          yield subquery == originalSubquery
              ? exists
              : new ExistsPredicate(subquery, exists.negated());
        }
        case ScalarSubqueryExpression<?> scalar -> {
          SelectStatement originalSubquery = scalar.subquery();
          SelectStatement subquery = select(originalSubquery);
          yield subquery == originalSubquery ? scalar : new ScalarSubqueryExpression<>(subquery);
        }
        case NotPredicate not -> {
          SqlPredicate originalOperand = not.operand();
          SqlPredicate operand = predicate(originalOperand);
          yield operand == originalOperand ? not : new NotPredicate(operand);
        }
        case IncrementExpression<?> increment -> {
          SqlExpression<?> originalOperand = increment.operand();
          SqlExpression<?> operand = expression(originalOperand);
          yield operand == originalOperand
              ? increment
              : new IncrementExpression<>(unchecked(operand));
        }
        default ->
            throw new IllegalArgumentException(
                "portable normalization does not support expression node "
                    + expression.getClass().getName());
      };
    }

    private static <T> List<T> mapIfChanged(List<T> source, UnaryOperator<T> mapper) {
      List<T> result = null;
      int index = 0;
      for (T item : source) {
        T normalized = mapper.apply(item);
        if (result == null && normalized != item) {
          result = new ArrayList<>(source.size());
          result.addAll(source.subList(0, index));
        }
        if (result != null) {
          result.add(normalized);
        }
        index++;
      }
      return result == null ? source : result;
    }

    private SqlPredicate predicate(SqlPredicate predicate) {
      SqlExpression<?> normalized = expression(predicate);
      if (normalized == predicate) {
        return predicate;
      }
      if (!(normalized instanceof SqlPredicate result)) {
        throw new IllegalStateException(
            "normalizing a predicate produced non-predicate node "
                + normalized.getClass().getName());
      }
      return result;
    }

    private <T> SqlExpression<?> in(InPredicate<T> in) {
      if (in.candidates().isEmpty()) {
        return new BooleanPredicate(in.negated());
      }
      SqlExpression<T> value = unchecked(expression(in.value()));
      boolean changed = value != in.value();
      List<SqlExpression<T>> candidates = new ArrayList<>(in.candidates().size());
      for (SqlExpression<T> candidate : in.candidates()) {
        SqlExpression<T> normalized = unchecked(expression(candidate));
        changed |= normalized != candidate;
        candidates.add(normalized);
      }
      if (!changed) {
        return in;
      }
      return new InPredicate<>(value, candidates, in.negated());
    }

    private static int selectedIndex(
        List<SqlExpression<?>> selections, SqlExpression<?> expression) {
      for (int index = 0; index < selections.size(); index++) {
        if (selections.get(index) == expression) {
          return index;
        }
      }
      for (int index = 0; index < selections.size(); index++) {
        if (selections.get(index).equals(expression)) {
          return index;
        }
      }
      return -1;
    }

    private static SqlExpression<Integer> nullRank(
        SqlExpression<?> expression, NullOrder nullOrder) {
      boolean first = nullOrder == NullOrder.FIRST;
      SqlExpression<Integer> nullResult =
          first ? LiteralExpression.zero(Integer.class) : LiteralExpression.one(Integer.class);
      SqlExpression<Integer> otherResult =
          first ? LiteralExpression.one(Integer.class) : LiteralExpression.zero(Integer.class);
      return new CaseExpression<>(
          List.of(new CaseWhen<>(new NullPredicate(expression, NullOperator.IS_NULL), nullResult)),
          otherResult);
    }

    @SuppressWarnings("unchecked")
    private static <T> SqlExpression<T> unchecked(SqlExpression<?> expression) {
      return (SqlExpression<T>) expression;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CaseExpression<?> caseExpression(
        List<CaseWhen<?>> branches, @Nullable SqlExpression<?> otherwise) {
      return new CaseExpression(
          branches, otherwise == null ? Optional.empty() : Optional.of(otherwise));
    }

    @SuppressWarnings("unchecked")
    private static SqlExpression<String> stringExpression(SqlExpression<?> expression) {
      return (SqlExpression<String>) expression;
    }
  }
}
