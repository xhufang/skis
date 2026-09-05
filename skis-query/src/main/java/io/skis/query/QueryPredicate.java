package io.skis.query;

import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlPredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable, entity-typed predicate tree whose runtime values remain outside the SQL AST. */
public final class QueryPredicate<E> implements QueryCondition {

  private final Node<E> root;

  private QueryPredicate(Node<E> root) {
    this.root = Objects.requireNonNull(root, "root");
  }

  static <E, V> QueryPredicate<E> comparison(
      QueryColumn<E, V> column, ComparisonOperator operator, V value) {
    return new QueryPredicate<>(new ComparisonNode<>(column, operator, value));
  }

  static <E> QueryPredicate<E> nullCheck(QueryColumn<E, ?> column, NullOperator operator) {
    return new QueryPredicate<>(new NullNode<>(column, operator));
  }

  static <E, V> QueryPredicate<E> between(QueryColumn<E, V> column, V lower, V upper) {
    return new QueryPredicate<>(new BetweenNode<>(column, lower, upper));
  }

  static <E, V> QueryPredicate<E> like(QueryColumn<E, V> column, V pattern) {
    return new QueryPredicate<>(new LikeNode<>(column, pattern));
  }

  static <E, V> QueryPredicate<E> in(QueryColumn<E, V> column, List<V> values, boolean negated) {
    return new QueryPredicate<>(new InNode<>(column, values, negated));
  }

  /** Returns a new grouped predicate combining this predicate and {@code other} with AND. */
  public QueryPredicate<E> and(QueryPredicate<E> other) {
    return logical(LogicalOperator.AND, other);
  }

  @Override
  public QueryCondition and(QueryCondition other) {
    return QueryConditions.logical(
        LogicalOperator.AND, this, Objects.requireNonNull(other, "other"));
  }

  /** Returns a new grouped predicate combining this predicate and {@code other} with OR. */
  public QueryPredicate<E> or(QueryPredicate<E> other) {
    return logical(LogicalOperator.OR, other);
  }

  @Override
  public QueryCondition or(QueryCondition other) {
    return QueryConditions.logical(
        LogicalOperator.OR, this, Objects.requireNonNull(other, "other"));
  }

  /** Returns a new grouped predicate representing SQL three-valued NOT. */
  @Override
  public QueryPredicate<E> not() {
    return new QueryPredicate<>(new NotNode<>(root));
  }

  CompiledQueryPredicate<E> compile() {
    QueryConditionCompiler compiler = new QueryConditionCompiler();
    SqlPredicate ast = root.compile(compiler);
    List<PropertyMeta<E, ?>> properties = new ArrayList<>(compiler.parameterColumns().size());
    for (QueryColumn<?, ?> column : compiler.parameterColumns()) {
      properties.add(property(column));
    }
    return new CompiledQueryPredicate<>(ast, properties, compiler.arguments());
  }

  SqlPredicate compile(QueryConditionCompiler compiler) {
    return root.compile(Objects.requireNonNull(compiler, "compiler"));
  }

  @Nullable PropertyMeta<E, ?> simpleEqualityProperty(QueryTable<E> table) {
    QueryColumn<E, ?> column = root.simpleEqualityColumn();
    return column != null && column.expression().table() == table ? column.property() : null;
  }

  private QueryPredicate<E> logical(LogicalOperator operator, QueryPredicate<E> other) {
    Objects.requireNonNull(other, "other");
    return new QueryPredicate<>(new LogicalNode<>(operator, root, other.root));
  }

  private sealed interface Node<E>
      permits ComparisonNode, NullNode, BetweenNode, LikeNode, InNode, LogicalNode, NotNode {

    SqlPredicate compile(QueryConditionCompiler compiler);

    default @Nullable QueryColumn<E, ?> simpleEqualityColumn() {
      return null;
    }
  }

  private record ComparisonNode<E, V>(
      QueryColumn<E, V> column, ComparisonOperator operator, V value) implements Node<E> {

    private ComparisonNode {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(value, "value");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new ComparisonPredicate<>(
          column.expression(), operator, compiler.parameter(column, value));
    }

    @Override
    public @Nullable QueryColumn<E, ?> simpleEqualityColumn() {
      return operator == ComparisonOperator.EQUAL ? column : null;
    }
  }

  private record NullNode<E>(QueryColumn<E, ?> column, NullOperator operator) implements Node<E> {

    private NullNode {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(operator, "operator");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new NullPredicate(column.expression(), operator);
    }
  }

  private record BetweenNode<E, V>(QueryColumn<E, V> column, V lower, V upper) implements Node<E> {

    private BetweenNode {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(lower, "lower");
      Objects.requireNonNull(upper, "upper");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new BetweenPredicate<>(
          column.expression(),
          compiler.parameter(column, lower),
          compiler.parameter(column, upper));
    }
  }

  private record LikeNode<E, V>(QueryColumn<E, V> column, V pattern) implements Node<E> {

    private LikeNode {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(pattern, "pattern");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new LikePredicate(column.expression(), compiler.parameter(column, pattern));
    }
  }

  private record InNode<E, V>(QueryColumn<E, V> column, List<V> values, boolean negated)
      implements Node<E> {

    private InNode {
      Objects.requireNonNull(column, "column");
      values = List.copyOf(values);
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      List<ParameterSlot<V>> candidates = new ArrayList<>(values.size());
      for (V value : values) {
        candidates.add(compiler.parameter(column, value));
      }
      return new InPredicate<>(column.expression(), candidates, negated);
    }
  }

  private record LogicalNode<E>(LogicalOperator operator, Node<E> left, Node<E> right)
      implements Node<E> {

    private LogicalNode {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new LogicalPredicate(
          operator, List.of(left.compile(compiler), right.compile(compiler)));
    }
  }

  private record NotNode<E>(Node<E> operand) implements Node<E> {

    private NotNode {
      Objects.requireNonNull(operand, "operand");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new NotPredicate(operand.compile(compiler));
    }
  }

  @SuppressWarnings("unchecked")
  private static <E> PropertyMeta<E, ?> property(QueryColumn<?, ?> column) {
    return (PropertyMeta<E, ?>) column.property();
  }
}

record CompiledQueryPredicate<E>(
    SqlPredicate ast, List<PropertyMeta<E, ?>> properties, List<Object> arguments) {

  CompiledQueryPredicate {
    Objects.requireNonNull(ast, "ast");
    properties = List.copyOf(properties);
    arguments = List.copyOf(arguments);
    if (properties.size() != arguments.size()) {
      throw new IllegalArgumentException("predicate property and argument counts differ");
    }
  }
}

record QueryArguments(List<Object> values) {

  QueryArguments {
    values = List.copyOf(values);
  }
}
