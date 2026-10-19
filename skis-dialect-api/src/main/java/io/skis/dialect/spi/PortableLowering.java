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
import org.jspecify.annotations.Nullable;

/**
 * Portable structural normalization applied to every final statement before post-validation and
 * final parameter layout.
 *
 * <p>The normalizer owns the four framework behaviors that previously lived in renderers: NULL
 * ordering fallback, nullable DISTINCT count compensation, selected-output references for DISTINCT
 * scalar ordering, and deterministic empty {@code IN}/{@code NOT IN}. It only rebuilds AST nodes
 * and never reads a parameter value or emits SQL text. Returning the exact input instance means that
 * no portable conversion was needed.
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
        boolean changed = false;
        List<SqlExpression<?>> selections = new ArrayList<>(select.selections().size());
        for (SqlExpression<?> selection : select.selections()) {
          SqlExpression<?> normalized = expression(selection);
          changed |= normalized != selection;
          selections.add(normalized);
        }

        List<HiddenSelection> hidden = new ArrayList<>(select.hiddenSelections().size());
        for (HiddenSelection item : select.hiddenSelections()) {
          SqlExpression<?> normalized = expression(item.expression());
          changed |= normalized != item.expression();
          hidden.add(normalized == item.expression() ? item : new HiddenSelection(normalized, item.alias()));
        }

        FromClause fromClause = fromClause(select.fromClause());
        changed |= fromClause != select.fromClause();

        SqlPredicate where = select.where().map(this::predicate).orElse(null);
        changed |= where != select.where().orElse(null);

        List<SqlExpression<?>> groupBy = new ArrayList<>(select.groupBy().size());
        for (SqlExpression<?> item : select.groupBy()) {
          SqlExpression<?> normalized = expression(item);
          changed |= normalized != item;
          groupBy.add(normalized);
        }

        SqlPredicate having = select.having().map(this::predicate).orElse(null);
        changed |= having != select.having().orElse(null);

        List<OrderByItem> orderBy = new ArrayList<>(select.orderBy().size());
        for (OrderByItem item : select.orderBy()) {
          SqlExpression<?> normalized = expression(item.expression());
          changed |= normalized != item.expression();

          if (select.distinct() && normalized instanceof ScalarSubqueryExpression<?>) {
            int selected = selectedIndex(selections, normalized);
            if (selected < 0) {
              throw new IllegalArgumentException(
                  "DISTINCT scalar ORDER BY must reference a selected expression");
            }
            normalized = SelectedOutputReference.of(selected + 1, normalized);
            changed = true;
          }

          NullOrder nullOrder = item.nullOrder();
          if (nullOrder != NullOrder.DIALECT_DEFAULT && !nativeNullOrdering) {
            if (normalized instanceof SelectedOutputReference<?>) {
              throw new IllegalArgumentException(
                  "DISTINCT scalar output ordering requires native NULLS FIRST/LAST support");
            }
            orderBy.add(
                new OrderByItem(
                    nullRank(normalized, nullOrder),
                    OrderDirection.ASC,
                    NullOrder.DIALECT_DEFAULT));
            orderBy.add(
                new OrderByItem(normalized, item.direction(), NullOrder.DIALECT_DEFAULT));
            changed = true;
            continue;
          }
          orderBy.add(new OrderByItem(normalized, item.direction(), nullOrder));
        }

        SelectPagination pagination = pagination(select.pagination().orElse(null));
        changed |= pagination != select.pagination().orElse(null);

        if (!changed) {
          return select;
        }
        return new SelectStatement(
            select.distinct(),
            selections,
            hidden,
            fromClause,
            where,
            groupBy,
            having,
            orderBy,
            pagination);
      }

      private CountAst count(CountAst count) {
        boolean changed = false;
        FromClause fromClause = fromClause(count.fromClause());
        changed |= fromClause != count.fromClause();
        SqlPredicate predicate = count.predicate().map(this::predicate).orElse(null);
        changed |= predicate != count.predicate().orElse(null);
        SqlExpression<?> distinct =
            count.distinctExpression().map(this::expression).orElse(null);
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
            SqlExpression<?> left = expression(arithmetic.left());
            SqlExpression<?> right = expression(arithmetic.right());
            yield left == arithmetic.left() && right == arithmetic.right()
                ? arithmetic
                : new ArithmeticExpression<>(
                    unchecked(left), arithmetic.operator(), unchecked(right));
          }
          case ConcatExpression concat -> {
            List<SqlExpression<String>> operands = new ArrayList<>(concat.operands().size());
            boolean changed = false;
            for (SqlExpression<String> operand : concat.operands()) {
              SqlExpression<?> normalized = expression(operand);
              changed |= normalized != operand;
              operands.add(stringExpression(normalized));
            }
            yield changed ? new ConcatExpression(operands) : concat;
          }
          case CaseExpression<?> caseExpression -> {
            boolean changed = false;
            List<CaseWhen<?>> branches = new ArrayList<>(caseExpression.branches().size());
            for (CaseWhen<?> branch : caseExpression.branches()) {
              SqlPredicate condition = predicate(branch.condition());
              SqlExpression<?> result = expression(branch.result());
              changed |= condition != branch.condition() || result != branch.result();
              branches.add(
                  condition == branch.condition() && result == branch.result()
                      ? branch
                      : new CaseWhen<>(condition, unchecked(result)));
            }
            SqlExpression<?> otherwise =
                caseExpression.otherwise().map(this::expression).orElse(null);
            changed |= otherwise != caseExpression.otherwise().orElse(null);
            if (!changed) {
              yield caseExpression;
            }
            yield caseExpression(branches, otherwise);
          }
          case CastExpression<?> cast -> {
            SqlExpression<?> operand = expression(cast.operand());
            yield operand == cast.operand()
                ? cast
                : new CastExpression<>(operand, cast.javaType(), cast.sqlType());
          }
          case CoalesceExpression<?> coalesce -> coalesce(coalesce);
          case ComparisonPredicate<?> comparison -> {
            SqlExpression<?> left = expression(comparison.left());
            SqlExpression<?> right = expression(comparison.right());
            yield left == comparison.left() && right == comparison.right()
                ? comparison
                : new ComparisonPredicate<>(
                    unchecked(left), comparison.operator(), unchecked(right));
          }
          case LogicalPredicate logical -> {
            boolean changed = false;
            List<SqlPredicate> operands = new ArrayList<>(logical.operands().size());
            for (SqlPredicate operand : logical.operands()) {
              SqlPredicate normalized = predicate(operand);
              changed |= normalized != operand;
              operands.add(normalized);
            }
            yield changed ? new LogicalPredicate(logical.operator(), operands) : logical;
          }
          case NullPredicate nullPredicate -> {
            SqlExpression<?> operand = expression(nullPredicate.operand());
            yield operand == nullPredicate.operand()
                ? nullPredicate
                : new NullPredicate(operand, nullPredicate.operator());
          }
          case BetweenPredicate<?> between -> {
            SqlExpression<?> value = expression(between.value());
            SqlExpression<?> lower = expression(between.lower());
            SqlExpression<?> upper = expression(between.upper());
            yield value == between.value() && lower == between.lower() && upper == between.upper()
                ? between
                : new BetweenPredicate<>(
                    unchecked(value), unchecked(lower), unchecked(upper));
          }
          case LikePredicate like -> {
            SqlExpression<?> value = expression(like.value());
            SqlExpression<?> pattern = expression(like.pattern());
            yield value == like.value() && pattern == like.pattern()
                ? like
                : new LikePredicate(value, pattern);
          }
          case InPredicate<?> in -> in(in);
          case InSubqueryPredicate<?> in -> {
            SqlExpression<?> value = expression(in.value());
            SelectStatement subquery = select(in.subquery());
            yield value == in.value() && subquery == in.subquery()
                ? in
                : new InSubqueryPredicate<>(unchecked(value), subquery, in.negated());
          }
          case ExistsPredicate exists -> {
            SelectStatement subquery = select(exists.subquery());
            yield subquery == exists.subquery()
                ? exists
                : new ExistsPredicate(subquery, exists.negated());
          }
          case ScalarSubqueryExpression<?> scalar -> {
            SelectStatement subquery = select(scalar.subquery());
            yield subquery == scalar.subquery()
                ? scalar
                : new ScalarSubqueryExpression<>(subquery);
          }
          case NotPredicate not -> {
            SqlPredicate operand = predicate(not.operand());
            yield operand == not.operand() ? not : new NotPredicate(operand);
          }
          case IncrementExpression<?> increment -> {
            SqlExpression<?> operand = expression(increment.operand());
            yield operand == increment.operand()
                ? increment
                : new IncrementExpression<>(unchecked(operand));
          }
          default ->
              throw new IllegalArgumentException(
                  "portable normalization does not support expression node "
                      + expression.getClass().getName());
        };
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

      private static int selectedIndex(List<SqlExpression<?>> selections, SqlExpression<?> expression) {
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
            first
                ? LiteralExpression.zero(Integer.class)
                : LiteralExpression.one(Integer.class);
        SqlExpression<Integer> otherResult =
            first
                ? LiteralExpression.one(Integer.class)
                : LiteralExpression.zero(Integer.class);
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
             branches,
            otherwise == null ? Optional.empty() : Optional.of(otherwise));
      }

      @SuppressWarnings("unchecked")
      private static SqlExpression<String> stringExpression(SqlExpression<?> expression) {
        return (SqlExpression<String>) expression;
      }
    }
}
