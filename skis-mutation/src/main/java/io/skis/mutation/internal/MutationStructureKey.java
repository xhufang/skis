package io.skis.mutation.internal;

import io.skis.metadata.EntityMode;
import io.skis.metadata.TableMeta;
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
import io.skis.sql.ast.ExcludedExpression;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.MergeStatement;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.ReturningStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    INCREMENT
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
          if (merge.source() != null) {
            parts.add(merge.source().reference().alias());
            outputs(merge.source().reference().outputs());
            expressions(merge.source().parameters());
          }
          expression(merge.on());
          expression(merge.matchedWhere());
          parts.add(merge.assignments().size());
          for (var assignment : merge.assignments()) {
            expression(assignment.column());
            expression(assignment.value());
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
        }
        case UpdateStatement update -> {
          parts.add(Kind.UPDATE);
          table(update.target());
          parts.add(update.assignments().size());
          for (var assignment : update.assignments()) {
            expression(assignment.column());
            expression(assignment.value());
          }
          expression(update.where());
        }
        case DeleteStatement delete -> {
          parts.add(Kind.DELETE);
          table(delete.target());
          expression(delete.where());
        }
        default -> throw new IllegalArgumentException("unsupported mutation structure");
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
