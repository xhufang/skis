package io.skis.mutation.internal;

import io.skis.metadata.EntityMode;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.ConflictInsertStatement;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.EntityRelationSource;
import io.skis.sql.ast.ExcludedExpression;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.MergeStatement;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ReturningStatement;
import io.skis.sql.ast.RowAssignment;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Detached, flat structural descriptors for the single-target mutation grammar. */
final class MutationStructureKey {
  private MutationStructureKey() {}

  /** Runs once per request lookup; no AST, table, property or application Class is retained. */
  static List<Object> of(StatementAst statement) {
    Builder builder = new Builder();
    builder.statement(statement);
    return List.copyOf(builder.parts);
  }

  // Fixed arity nodes and explicit list lengths make the flattened tree unambiguous.
  private enum Kind {
    RETURNING,
    INSERT,
    CONFLICT,
    MERGE,
    EXCLUDED,
    DERIVED,
    UPDATE,
    DELETE,
    COLUMN,
    PARAMETER,
    BOOLEAN,
    LITERAL,
    ARITHMETIC,
    CONCAT,
    CASE,
    CAST,
    COALESCE,
    COMPARISON,
    LOGICAL,
    NULL_CHECK,
    BETWEEN,
    LIKE,
    IN,
    NOT,
    INCREMENT,
    SELECT,
    SOURCE,
    ROW_ASSIGNMENT,
    IN_SUBQUERY,
    EXISTS,
    SCALAR_SUBQUERY,
    AGGREGATE,
    SELECTED_OUTPUT
  }

  private record TableShape(
      String javaTypeName, String entityName, EntityMode mode, TableMeta table, String alias) {
    private static TableShape of(TableExpression<?> table) {
      var entity = table.entity();
      return new TableShape(
          entity.javaType().getName(),
          entity.entityName(),
          entity.mode(),
          entity.table(),
          table.alias().map(Identifier::value).orElse(""));
    }
  }

  /** Mutation AST validation has already restricted all columns to the one statement target. */
  private static final class Builder {
    private final List<Object> parts = new ArrayList<>();

    private void statement(StatementAst statement) {
      switch (statement) {
        case ReturningStatement returning -> {
          parts.add(Kind.RETURNING);
          parts.add(returning.form());
          statement(returning.mutation());
          expressions(returning.selections());
        }
        case ConflictInsertStatement conflict -> {
          parts.add(Kind.CONFLICT);
          parts.add(conflict.doNothing());
          statement(conflict.insert());
          expressions(conflict.conflictColumns());
          parts.add(conflict.assignments().size());
          for (var assignment : conflict.assignments()) {
            expression(assignment.column());
            expression(assignment.value());
          }
          expression(conflict.updateWhere());
        }
        case MergeStatement merge -> {
          parts.add(Kind.MERGE);
          parts.add(merge.keyForm());
          table(merge.target());
          if (merge.source() != null) relation(merge.source());
          expression(merge.on());
          parts.add(merge.actions().size());
          for (var action : merge.actions()) {
            parts.add(action.kind());
            expression(action.condition());
            parts.add(action.assignments().size());
            for (var item : action.assignments()) {
              if (item
                  instanceof
                  UpdateAssignment<?>(ColumnExpression<?, ?> column, SqlExpression<?> value)) {
                parts.add(Kind.UPDATE);
                expression(column);
                expression(value);
              } else if (item
                  instanceof
                  RowAssignment(List<ColumnExpression<?, ?>> columns, SelectStatement query)) {
                parts.add(Kind.ROW_ASSIGNMENT);
                expressions(columns);
                statement(query);
              }
            }
            expressions(action.columns());
            expressions(action.values());
          }
          expressions(merge.insertColumns());
          expressions(merge.insertValues());
          expressions(merge.keys());
        }
        case InsertStatement insert -> {
          parts.add(Kind.INSERT);
          table(insert.target());
          expressions(insert.columns());
          expressions(insert.values());
          parts.add(insert.additionalRows().size());
          for (var row : insert.additionalRows()) expressions(row);
          parts.add(insert.query() != null);
          if (insert.query() != null) statement(insert.query());
        }
        case SelectStatement select -> {
          parts.add(Kind.SELECT);
          parts.add(select.distinct());
          expressions(select.selections());
          parts.add(select.hiddenSelections().size());
          for (var hidden : select.hiddenSelections()) {
            parts.add(hidden.alias());
            expression(hidden.expression());
          }
          from(select.fromClause());
          optional(select.where().orElse(null));
          expressions(select.groupBy());
          optional(select.having().orElse(null));
          parts.add(select.orderBy().size());
          for (var order : select.orderBy()) {
            parts.add(order.direction());
            parts.add(order.nullOrder());
            expression(order.expression());
          }
          var pagination = select.pagination().orElse(null);
          parts.add(pagination == null ? "none" : pagination.getClass().getName());
          if (pagination != null) {
            if (pagination instanceof KeysetSeek seek) expression(seek.predicate());
            expression(pagination.limit());
            if (pagination instanceof OffsetLimit offset) expression(offset.offset());
          }
        }
        case UpdateStatement update -> {
          parts.add(Kind.UPDATE);
          table(update.target());
          parts.add(update.assignments().size());
          for (var item : update.assignments()) {
            if (item
                instanceof
                UpdateAssignment<?>(ColumnExpression<?, ?> column, SqlExpression<?> value)) {
              parts.add(Kind.UPDATE);
              expression(column);
              expression(value);
            } else if (item
                instanceof
                RowAssignment(List<ColumnExpression<?, ?>> columns, SelectStatement query)) {
              parts.add(Kind.ROW_ASSIGNMENT);
              expressions(columns);
              statement(query);
            }
          }
          parts.add(update.from() != null);
          if (update.from() != null) from(update.from());
          expression(update.where());
        }
        case DeleteStatement delete -> {
          parts.add(Kind.DELETE);
          table(delete.target());
          parts.add(delete.using() != null);
          if (delete.using() != null) from(delete.using());
          expression(delete.where());
        }
        default -> throw new IllegalArgumentException("unsupported mutation structure");
      }
    }

    private void optional(@Nullable SqlExpression<?> expression) {
      parts.add(expression != null);
      if (expression != null) expression(expression);
    }

    private void from(FromClause from) {
      relation(from.root());
      parts.add(from.joins().size());
      for (var join : from.joins()) {
        parts.add(join.type());
        relation(join.right());
        optional(join.on().orElse(null));
      }
    }

    private void relation(RelationSource source) {
      parts.add(Kind.SOURCE);
      switch (source) {
        case EntityRelationSource(TableExpression<?> table) -> {
          parts.add(Kind.COLUMN);
          table(table);
        }
        case DerivedRelationSource(
                SelectStatement statement,
                DerivedRelationReference reference) -> {
          parts.add(Kind.DERIVED);
          parts.add(reference.alias());
          outputs(reference.outputs());
          statement(statement);
        }
        case ParameterRelationSource(
                List<ParameterSlot<?>> parameters,
                DerivedRelationReference reference) -> {
          parts.add(Kind.PARAMETER);
          parts.add(reference.alias());
          outputs(reference.outputs());
          expressions(parameters);
        }
        case null, default ->
            throw new IllegalArgumentException("unsupported mutation source structure");
      }
    }

    private void outputs(List<DerivedOutputColumn> outputs) {
      parts.add(outputs.size());
      for (var output : outputs) {
        parts.add(output.name());
        parts.add(output.javaType().getName());
        parts.add(output.sqlType());
        parts.add(output.nullability());
      }
    }

    private void expressions(List<? extends SqlExpression<?>> expressions) {
      parts.add(expressions.size());
      for (SqlExpression<?> expression : expressions) {
        expression(expression);
      }
    }

    private void table(TableExpression<?> table) {
      parts.add(TableShape.of(table));
    }

    private void descriptor(SqlExpression<?> expression) {
      parts.add(expression.javaType().getName());
      parts.add(expression.sqlType());
      parts.add(expression.nullability());
    }

    private void expression(SqlExpression<?> expression) {
      switch (expression) {
        case InSubqueryPredicate<?> in -> {
          parts.add(Kind.IN_SUBQUERY);
          parts.add(in.negated());
          expression(in.value());
          statement(in.subquery());
        }
        case ExistsPredicate exists -> {
          parts.add(Kind.EXISTS);
          parts.add(exists.negated());
          statement(exists.subquery());
        }
        case ScalarSubqueryExpression<?> scalar -> {
          parts.add(Kind.SCALAR_SUBQUERY);
          statement(scalar.subquery());
        }
        case AggregateExpression<?> aggregate -> {
          parts.add(Kind.AGGREGATE);
          parts.add(aggregate.kind());
          parts.add(aggregate.distinct());
          optional(aggregate.operand().orElse(null));
          descriptor(aggregate);
        }
        case SelectedOutputReference<?> output -> {
          parts.add(Kind.SELECTED_OUTPUT);
          parts.add(output.position());
          descriptor(output);
        }
        case ExcludedExpression<?> excluded -> {
          parts.add(Kind.EXCLUDED);
          expression(excluded.column());
        }
        case DerivedColumnExpression<?> column -> {
          parts.add(Kind.DERIVED);
          parts.add(column.relation().alias());
          outputs(column.relation().outputs());
          parts.add(column.outputOrdinal());
        }
        case ColumnExpression<?, ?> column -> {
          parts.add(Kind.COLUMN);
          table(column.table());
          var property = column.property();
          parts.add(property.ordinal());
          parts.add(property.name());
          parts.add(property.javaType().getName());
          parts.add(property.column());
        }
        case ParameterSlot<?> parameter -> {
          parts.add(Kind.PARAMETER);
          parts.add(parameter.ordinal());
          descriptor(parameter);
        }
        case BooleanPredicate predicate -> {
          parts.add(Kind.BOOLEAN);
          parts.add(predicate.value());
        }
        case LiteralExpression<?> literal -> {
          parts.add(Kind.LITERAL);
          parts.add(literal.kind());
          descriptor(literal);
        }
        case ArithmeticExpression<?> arithmetic -> {
          parts.add(Kind.ARITHMETIC);
          parts.add(arithmetic.operator());
          expression(arithmetic.left());
          expression(arithmetic.right());
        }
        case ConcatExpression concat -> {
          parts.add(Kind.CONCAT);
          expressions(concat.operands());
        }
        case CaseExpression<?> conditional -> {
          parts.add(Kind.CASE);
          parts.add(conditional.branches().size());
          for (var branch : conditional.branches()) {
            expression(branch.condition());
            expression(branch.result());
          }
          parts.add(conditional.otherwise().isPresent());
          if (conditional.otherwise().isPresent()) {
            expression(conditional.otherwise().orElseThrow());
          }
        }
        case CastExpression<?> cast -> {
          parts.add(Kind.CAST);
          // Nullability follows the operand; do not walk it again for every nested CAST.
          parts.add(cast.javaType().getName());
          parts.add(cast.sqlType());
          parts.add(Optional.ofNullable(cast.decimalPrecision()));
          expression(cast.operand());
        }
        case CoalesceExpression<?> coalesce -> {
          parts.add(Kind.COALESCE);
          expressions(coalesce.operands());
        }
        case ComparisonPredicate<?> comparison -> {
          parts.add(Kind.COMPARISON);
          parts.add(comparison.operator());
          expression(comparison.left());
          expression(comparison.right());
        }
        case LogicalPredicate logical -> {
          parts.add(Kind.LOGICAL);
          parts.add(logical.operator());
          expressions(logical.operands());
        }
        case NullPredicate predicate -> {
          parts.add(Kind.NULL_CHECK);
          parts.add(predicate.operator());
          expression(predicate.operand());
        }
        case BetweenPredicate<?> between -> {
          parts.add(Kind.BETWEEN);
          expression(between.value());
          expression(between.lower());
          expression(between.upper());
        }
        case LikePredicate like -> {
          parts.add(Kind.LIKE);
          expression(like.value());
          expression(like.pattern());
        }
        case InPredicate<?> in -> {
          parts.add(Kind.IN);
          parts.add(in.negated());
          expression(in.value());
          expressions(in.candidates());
        }
        case NotPredicate not -> {
          parts.add(Kind.NOT);
          expression(not.operand());
        }
        case IncrementExpression<?> increment -> {
          parts.add(Kind.INCREMENT);
          expression(increment.operand());
        }
        default -> throw new IllegalArgumentException("unsupported mutation expression structure");
      }
    }
  }
}
