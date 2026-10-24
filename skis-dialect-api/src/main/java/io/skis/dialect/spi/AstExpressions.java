package io.skis.dialect.spi;

import io.skis.sql.ast.AggregateExpression;
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
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Compilation-local operand ordering shared by aggregate rewriting and correspondence checks. */
final class AstExpressions {

  private AstExpressions() {}

  static List<? extends SqlExpression<?>> operands(SqlExpression<?> expression) {
    return switch (expression) {
      case AggregateExpression<?> aggregate -> {
        SqlExpression<?> operand = aggregate.operand().orElse(null);
        yield operand == null ? List.of() : List.of(operand);
      }
      case ArithmeticExpression<?> arithmetic -> List.of(arithmetic.left(), arithmetic.right());
      case ConcatExpression concat -> concat.operands();
      case CaseExpression<?> conditional -> {
        List<SqlExpression<?>> children = new ArrayList<>(conditional.branches().size() * 2 + 1);
        for (CaseWhen<?> branch : conditional.branches()) {
          children.add(branch.condition());
          children.add(branch.result());
        }
        conditional.otherwise().ifPresent(children::add);
        yield children;
      }
      case CastExpression<?> cast -> List.of(cast.operand());
      case CoalesceExpression<?> coalesce -> coalesce.operands();
      case ComparisonPredicate<?> comparison -> List.of(comparison.left(), comparison.right());
      case LogicalPredicate logical -> logical.operands();
      case NullPredicate predicate -> List.of(predicate.operand());
      case BetweenPredicate<?> between ->
          List.of(between.value(), between.lower(), between.upper());
      case LikePredicate like -> List.of(like.value(), like.pattern());
      case InPredicate<?> in -> {
        List<SqlExpression<?>> children = new ArrayList<>(in.candidates().size() + 1);
        children.add(in.value());
        children.addAll(in.candidates());
        yield children;
      }
      case InSubqueryPredicate<?> in -> List.of(in.value());
      case NotPredicate not -> List.of(not.operand());
      case IncrementExpression<?> increment -> List.of(increment.operand());
      case ParameterSlot<?> ignored -> List.of();
      case BooleanPredicate ignored -> List.of();
      case ColumnExpression<?, ?> ignored -> List.of();
      case DerivedColumnExpression<?> ignored -> List.of();
      case LiteralExpression<?> ignored -> List.of();
      case SelectedOutputReference<?> ignored -> List.of();
      case ScalarSubqueryExpression<?> ignored -> List.of();
      case ExistsPredicate ignored -> List.of();
      default ->
          throw new IllegalArgumentException(
              "unsupported compilation expression " + expression.getClass().getName());
    };
  }

  static @Nullable SelectStatement subquery(SqlExpression<?> expression) {
    return switch (expression) {
      case ScalarSubqueryExpression<?> scalar -> scalar.subquery();
      case ExistsPredicate exists -> exists.subquery();
      case InSubqueryPredicate<?> in -> in.subquery();
      default -> null;
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  static SqlExpression<?> rebuild(
      SqlExpression<?> expression,
      List<? extends SqlExpression<?>> originalOperands,
      List<? extends SqlExpression<?>> operands,
      @Nullable SelectStatement subquery) {
    boolean changed = subquery != subquery(expression);
    for (int index = 0; index < operands.size(); index++) {
      changed |= originalOperands.get(index) != operands.get(index);
    }
    if (!changed) {
      return expression;
    }
    return switch (expression) {
      case AggregateExpression<?> aggregate -> aggregate.withOperand(operands.getFirst());
      case ArithmeticExpression<?> arithmetic ->
          new ArithmeticExpression(operands.get(0), arithmetic.operator(), operands.get(1));
      case ConcatExpression ignored -> new ConcatExpression((List) operands);
      case CaseExpression<?> conditional -> {
        List<CaseWhen<Object>> branches = new ArrayList<>(conditional.branches().size());
        for (int index = 0; index < conditional.branches().size(); index++) {
          branches.add(
              new CaseWhen((SqlPredicate) operands.get(index * 2), operands.get(index * 2 + 1)));
        }
        Optional<SqlExpression<Object>> otherwise =
            conditional.otherwise().isEmpty()
                ? Optional.empty()
                : Optional.of((SqlExpression<Object>) operands.getLast());
        yield new CaseExpression<>(branches, otherwise);
      }
      case CastExpression<?> cast ->
          new CastExpression<>(
              operands.getFirst(), cast.javaType(), cast.sqlType(), cast.decimalPrecision());
      case CoalesceExpression<?> ignored -> new CoalesceExpression(operands);
      case ComparisonPredicate<?> comparison ->
          new ComparisonPredicate(operands.get(0), comparison.operator(), operands.get(1));
      case LogicalPredicate logical -> new LogicalPredicate(logical.operator(), (List) operands);
      case NullPredicate predicate -> new NullPredicate(operands.getFirst(), predicate.operator());
      case BetweenPredicate<?> ignored ->
          new BetweenPredicate(operands.get(0), operands.get(1), operands.get(2));
      case LikePredicate ignored -> new LikePredicate(operands.get(0), operands.get(1));
      case InPredicate<?> in ->
          new InPredicate(operands.getFirst(), operands.subList(1, operands.size()), in.negated());
      case InSubqueryPredicate<?> in ->
          new InSubqueryPredicate(operands.getFirst(), requireSubquery(subquery), in.negated());
      case ExistsPredicate exists ->
          new ExistsPredicate(requireSubquery(subquery), exists.negated());
      case ScalarSubqueryExpression<?> ignored ->
          new ScalarSubqueryExpression<>(requireSubquery(subquery));
      case NotPredicate ignored -> new NotPredicate((SqlPredicate) operands.getFirst());
      case IncrementExpression<?> ignored -> new IncrementExpression(operands.getFirst());
      default ->
          throw new IllegalArgumentException(
              "cannot rebuild compilation expression " + expression.getClass().getName());
    };
  }

  private static SelectStatement requireSubquery(@Nullable SelectStatement subquery) {
    return java.util.Objects.requireNonNull(subquery, "subquery");
  }
}
