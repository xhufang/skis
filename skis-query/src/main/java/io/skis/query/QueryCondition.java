package io.skis.query;

import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlPredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Framework-owned immutable SQL condition that can retain references to more than one query table.
 *
 * <p>The sealed contract deliberately exposes neither raw SQL nor the underlying AST. Conditions
 * are created by typed query columns and can be composed without moving runtime values into the SQL
 * structure.
 */
public sealed interface QueryCondition permits QueryPredicate, FrameworkQueryCondition {

  /** Returns a new grouped condition combining both operands with SQL {@code AND}. */
  QueryCondition and(QueryCondition other);

  /** Returns a new grouped condition combining both operands with SQL {@code OR}. */
  QueryCondition or(QueryCondition other);

  /** Returns a new grouped condition representing SQL three-valued {@code NOT}. */
  QueryCondition not();
}

/** Internal condition used for column comparisons and combinations spanning entity types. */
final class FrameworkQueryCondition implements QueryCondition {

  private final Node root;

  private FrameworkQueryCondition(Node root) {
    this.root = Objects.requireNonNull(root, "root");
  }

  static <L, R, V> FrameworkQueryCondition comparison(
      QueryColumn<L, V> left, ComparisonOperator operator, QueryColumn<R, V> right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(right, "right");
    try {
      return new FrameworkQueryCondition(
          new PredicateNode(
              new ComparisonPredicate<V>(left.expression(), operator, right.expression())));
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }

  static QueryCondition logical(
      LogicalOperator operator, QueryCondition left, QueryCondition right) {
    return new FrameworkQueryCondition(new LogicalNode(operator, left, right));
  }

  static QueryCondition negate(QueryCondition operand) {
    return new FrameworkQueryCondition(new NotNode(operand));
  }

  @Override
  public QueryCondition and(QueryCondition other) {
    return logical(LogicalOperator.AND, this, Objects.requireNonNull(other, "other"));
  }

  @Override
  public QueryCondition or(QueryCondition other) {
    return logical(LogicalOperator.OR, this, Objects.requireNonNull(other, "other"));
  }

  @Override
  public QueryCondition not() {
    return negate(this);
  }

  SqlPredicate compile(QueryConditionCompiler compiler) {
    return root.compile(Objects.requireNonNull(compiler, "compiler"));
  }

  private sealed interface Node permits PredicateNode, LogicalNode, NotNode {

    SqlPredicate compile(QueryConditionCompiler compiler);
  }

  private record PredicateNode(SqlPredicate predicate) implements Node {

    private PredicateNode {
      Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return predicate;
    }
  }

  private record LogicalNode(LogicalOperator operator, QueryCondition left, QueryCondition right)
      implements Node {

    private LogicalNode {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new LogicalPredicate(
          operator,
          List.of(
              QueryConditions.compile(left, compiler), QueryConditions.compile(right, compiler)));
    }
  }

  private record NotNode(QueryCondition operand) implements Node {

    private NotNode {
      Objects.requireNonNull(operand, "operand");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new NotPredicate(QueryConditions.compile(operand, compiler));
    }
  }
}

/** Package-local bridge that keeps AST compilation off the public condition contract. */
final class QueryConditions {

  private QueryConditions() {}

  static QueryCondition logical(
      LogicalOperator operator, QueryCondition left, QueryCondition right) {
    return FrameworkQueryCondition.logical(operator, left, right);
  }

  static SqlPredicate compile(QueryCondition condition, QueryConditionCompiler compiler) {
    Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(compiler, "compiler");
    return switch (condition) {
      case QueryPredicate<?> predicate -> predicate.compile(compiler);
      case FrameworkQueryCondition framework -> framework.compile(compiler);
    };
  }
}

/** One statement-scoped allocator shared by JOIN ON and WHERE condition trees. */
final class QueryConditionCompiler {

  private final List<QueryColumn<?, ?>> parameterColumns = new ArrayList<>();
  private final List<Object> arguments = new ArrayList<>();

  <E, V> ParameterSlot<V> parameter(QueryColumn<E, V> column, V value) {
    Objects.requireNonNull(column, "column");
    Objects.requireNonNull(value, "value");
    int ordinal = arguments.size();
    parameterColumns.add(column);
    arguments.add(value);
    return new ParameterSlot<>(ordinal, column.javaType(), column.sqlType(), Nullability.NON_NULL);
  }

  List<QueryColumn<?, ?>> parameterColumns() {
    return List.copyOf(parameterColumns);
  }

  List<Object> arguments() {
    return List.copyOf(arguments);
  }
}
