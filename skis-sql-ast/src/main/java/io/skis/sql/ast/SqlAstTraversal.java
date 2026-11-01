package io.skis.sql.ast;

import io.skis.sql.ast.ReturningStatement.Form;
import java.util.Objects;
import java.util.function.Consumer;

/** Typed structural traversal for validation and lowering metadata; never collects JDBC values. */
public final class SqlAstTraversal {
  private SqlAstTraversal() {}

  public static void visit(
      StatementAst statement,
      Consumer<SqlExpression<?>> expressions,
      Consumer<RelationSource> relations) {
    new Walker(expressions, relations).statement(Objects.requireNonNull(statement, "statement"));
  }

  public static void visitExpressions(
      Iterable<? extends SqlExpression<?>> outputs,
      Consumer<SqlExpression<?>> expressions,
      Consumer<RelationSource> relations) {
    Walker walker = new Walker(expressions, relations);
    for (SqlExpression<?> output : outputs) walker.expression(output);
  }

  private record Walker(
      Consumer<SqlExpression<?>> expressions, Consumer<RelationSource> relations) {
    private Walker(Consumer<SqlExpression<?>> expressions, Consumer<RelationSource> relations) {
      this.expressions = Objects.requireNonNull(expressions, "expressions");
      this.relations = Objects.requireNonNull(relations, "relations");
    }

    private void statement(StatementAst statement) {
      switch (statement) {
        case ReturningStatement returning -> {
          if (returning.form() != Form.NATIVE) returning.selections().forEach(this::expression);
          statement(returning.mutation());
          if (returning.form() == Form.NATIVE) returning.selections().forEach(this::expression);
        }
        case SelectStatement select -> select(select);
        case CountAst count -> count(count);
        case ConflictInsertStatement conflict -> {
          statement(conflict.insert());
          conflict.assignments().forEach(value -> expression(value.value()));
          expression(conflict.updateWhere());
        }
        case MergeStatement merge -> {
          if (merge.source() != null) relation(merge.source());
          expression(merge.on());
          for (MergeAction action : merge.actions()) {
            expression(action.condition());
            for (var item : action.assignments()) {
              if (item instanceof UpdateAssignment<?> assignment) expression(assignment.value());
              else if (item instanceof RowAssignment row) select(row.query());
            }
            action.values().forEach(this::expression);
          }
          merge.insertValues().forEach(this::expression);
        }
        case InsertStatement insert -> {
          if (insert.query() != null) select(insert.query());
          else {
            insert.values().forEach(this::expression);
            for (var row : insert.additionalRows()) row.forEach(this::expression);
          }
        }
        case UpdateStatement update -> {
          for (var item : update.assignments()) {
            if (item instanceof UpdateAssignment<?> assignment) expression(assignment.value());
            else if (item instanceof RowAssignment row) select(row.query());
          }
          if (update.from() != null) from(update.from());
          expression(update.where());
        }
        case DeleteStatement delete -> {
          if (delete.using() != null) from(delete.using());
          expression(delete.where());
        }
        default ->
            throw new IllegalArgumentException(
                "AST traversal does not support statement node " + statement.getClass().getName());
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
      relations.accept(source);
      if (source instanceof DerivedRelationSource derived) {
        select(derived.statement());
      } else if (source instanceof ParameterRelationSource parameters) {
        parameters.parameters().forEach(this::expression);
      }
    }

    private void expression(SqlExpression<?> expression) {
      expressions.accept(expression);
      switch (expression) {
        case AggregateExpression<?> aggregate -> aggregate.operand().ifPresent(this::expression);
        case ParameterSlot<?> ignored -> {}
        case BooleanPredicate ignored -> {}
        case SelectedOutputReference<?> ignored -> {}
        case ExcludedExpression<?> ignored -> {}
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
          expression(in.value());
          in.candidates().forEach(this::expression);
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
                "AST traversal does not support expression node "
                    + expression.getClass().getName());
      }
    }
  }
}
