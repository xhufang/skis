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
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Value-independent, ordered JDBC parameter positions for one final statement structure.
 *
 * <p>The layout is collected from the lowered AST in exact render order after post-validation. It
 * never stores a parameter value, connection, or execution resource. A logical {@link
 * ParameterSlot} may appear more than once when lowering duplicates an expression, and a slot may
 * be absent when normalization legally pruned its expression.
 */
public final class ParameterLayout {

  private final List<ParameterPosition> positions;
  private final List<ParameterSlot<?>> slots;

  private ParameterLayout(List<ParameterPosition> positions) {
    this.positions = List.copyOf(positions);
    List<ParameterSlot<?>> ordered = new ArrayList<>(this.positions.size());
    for (ParameterPosition position : this.positions) {
      ordered.add(position.slot());
    }
    this.slots = List.copyOf(ordered);
  }

  /**
   * Collects final JDBC positions from a complete statement structure in render order.
   *
   * <p>This method is a pure structural operation. It is called after lowering and post-validation
   * and performs no analysis, capability decision, or database access.
   */
  public static ParameterLayout collect(StatementAst statement) {
    Objects.requireNonNull(statement, "statement");
    Collector collector = new Collector();
    collector.statement(statement);
    return new ParameterLayout(collector.positions);
  }

  /** Ordered one-based JDBC positions and their logical slots. */
  public List<ParameterPosition> positions() {
    return positions;
  }

  /** Ordered logical slots in placeholder encounter order. */
  public List<ParameterSlot<?>> slots() {
    return slots;
  }

  /** Number of placeholders in the final SQL template. */
  public int size() {
    return positions.size();
  }

  /** Whether the final statement has no placeholders. */
  public boolean isEmpty() {
    return positions.isEmpty();
  }

  /** One one-based JDBC placeholder position. */
  public record ParameterPosition(int jdbcIndex, ParameterSlot<?> slot) {

    public ParameterPosition {
      if (jdbcIndex < 1) {
        throw new IllegalArgumentException("jdbcIndex must be one-based");
      }
      Objects.requireNonNull(slot, "slot");
    }
  }

  private static final class Collector {

    private final List<ParameterPosition> positions = new ArrayList<>();

    private void statement(StatementAst statement) {
      switch (statement) {
        case SelectStatement select -> select(select);
        case CountAst count -> count(count);
        case InsertStatement insert -> insert.values().forEach(this::expression);
        case UpdateStatement update -> {
          for (UpdateAssignment<?> assignment : update.assignments()) {
            expression(assignment.value());
          }
          expression(update.where());
        }
        case DeleteStatement delete -> expression(delete.where());
        default ->
            throw new IllegalArgumentException(
                "parameter layout does not support statement node "
                    + statement.getClass().getName());
      }
    }

    private void select(SelectStatement select) {
      select.selections().forEach(this::expression);
      select.hiddenSelections().forEach(hidden -> expression(hidden.expression()));
      from(select.fromClause());
      select.where().ifPresent(this::expression);
      select.groupBy().forEach(this::expression);
      select.having().ifPresent(this::expression);
      if (select.pagination().orElse(null) instanceof KeysetSeek keyset) {
        expression(keyset.predicate());
      }
      for (OrderByItem item : select.orderBy()) {
        expression(item.expression());
      }
      select
          .pagination()
          .ifPresent(
              pagination -> {
                expression(pagination.limit());
                if (pagination instanceof OffsetLimit offset) {
                  expression(offset.offset());
                }
              });
    }

    private void count(CountAst count) {
      count.distinctExpression().ifPresent(this::expression);
      if (count.nullCompensated()) {
        // COUNT(DISTINCT e) + CASE WHEN COUNT(*) > COUNT(e) ...
        expression(count.distinctExpression().orElseThrow());
      }
      from(count.fromClause());
      count.predicate().ifPresent(this::expression);
    }

    private void from(FromClause fromClause) {
      relation(fromClause.root());
      for (JoinClause join : fromClause.joins()) {
        relation(join.right());
        join.on().ifPresent(this::expression);
      }
    }

    private void relation(RelationSource source) {
      if (source instanceof DerivedRelationSource derived) {
        select(derived.statement());
      }
    }

    private void expression(SqlExpression<?> expression) {
      switch (expression) {
        case ParameterSlot<?> parameter -> add(parameter);
        case BooleanPredicate ignored -> {}
        case SelectedOutputReference<?> ignored -> {}
        case ColumnExpression<?, ?> ignored -> {}
        case DerivedColumnExpression<?> ignored -> {}
        case LiteralExpression<?> ignored -> {}
        case ArithmeticExpression<?> arithmetic -> {
          expression(arithmetic.left());
          expression(arithmetic.right());
        }
        case ConcatExpression concat -> concat.operands().forEach(this::expression);
        case CaseExpression<?> caseExpression -> {
          for (CaseWhen<?> branch : caseExpression.branches()) {
            expression(branch.condition());
            expression(branch.result());
          }
          caseExpression.otherwise().ifPresent(this::expression);
        }
        case CastExpression<?> cast -> expression(cast.operand());
        case CoalesceExpression<?> coalesce -> coalesce.operands().forEach(this::expression);
        case ComparisonPredicate<?> comparison -> {
          expression(comparison.left());
          expression(comparison.right());
        }
        case LogicalPredicate logical -> logical.operands().forEach(this::expression);
        case NullPredicate nullPredicate -> expression(nullPredicate.operand());
        case BetweenPredicate<?> between -> {
          expression(between.value());
          expression(between.lower());
          expression(between.upper());
        }
        case LikePredicate like -> {
          expression(like.value());
          expression(like.pattern());
        }
        case InPredicate<?> in -> {
          if (!in.candidates().isEmpty()) {
            expression(in.value());
            in.candidates().forEach(this::expression);
          }
        }
        case InSubqueryPredicate<?> in -> {
          expression(in.value());
          select(in.subquery());
        }
        case ExistsPredicate exists -> select(exists.subquery());
        case ScalarSubqueryExpression<?> scalar -> select(scalar.subquery());
        case NotPredicate not -> expression(not.operand());
        case IncrementExpression<?> increment -> expression(increment.operand());
        default ->
            throw new IllegalArgumentException(
                "parameter layout does not support expression node "
                    + expression.getClass().getName());
      }
    }

    private void add(ParameterSlot<?> slot) {
      positions.add(new ParameterPosition(positions.size() + 1, slot));
    }
  }
}
