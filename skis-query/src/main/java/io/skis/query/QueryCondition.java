package io.skis.query;

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

/** Framework-owned immutable SQL condition over one or more selectable expressions. */
public sealed interface QueryCondition permits FrameworkQueryCondition {

  /** Returns a new grouped condition combining both operands with SQL {@code AND}. */
  QueryCondition and(QueryCondition other);

  /** Returns a new grouped condition combining both operands with SQL {@code OR}. */
  QueryCondition or(QueryCondition other);

  /** Returns a new grouped condition representing SQL three-valued {@code NOT}. */
  QueryCondition not();
}

/** The single condition implementation used by both physical columns and standard expressions. */
final class FrameworkQueryCondition implements QueryCondition {

  private final Node root;
  private final QueryParameters parameters;

  private FrameworkQueryCondition(Node root, QueryParameters parameters) {
    this.root = Objects.requireNonNull(root, "root");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
  }

  static <V> FrameworkQueryCondition valueComparison(
      Selectable<V> left, ComparisonOperator operator, V value) {
    QueryParameter<V> parameter = QueryParameter.anonymousNonNull(left.javaType());
    return new FrameworkQueryCondition(
        new ValueComparisonNode<>(left, operator, parameter), QueryParameters.of(parameter, value));
  }

  static <V> FrameworkQueryCondition parameterComparison(
      Selectable<V> left, ComparisonOperator operator, QueryParameter<V> parameter) {
    return new FrameworkQueryCondition(
        new ValueComparisonNode<>(left, operator, parameter), QueryParameters.empty());
  }

  static <V> FrameworkQueryCondition expressionComparison(
      Selectable<V> left, ComparisonOperator operator, Selectable<V> right) {
    return new FrameworkQueryCondition(
        new ExpressionComparisonNode<>(left, operator, right), QueryParameters.empty());
  }

  static FrameworkQueryCondition nullCheck(Selectable<?> selectable, NullOperator operator) {
    return new FrameworkQueryCondition(new NullNode(selectable, operator), QueryParameters.empty());
  }

  static <V> FrameworkQueryCondition between(Selectable<V> value, V lower, V upper) {
    QueryParameter<V> lowerParameter = QueryParameter.anonymousNonNull(value.javaType());
    QueryParameter<V> upperParameter = QueryParameter.anonymousNonNull(value.javaType());
    return new FrameworkQueryCondition(
        new BetweenNode<>(value, lowerParameter, upperParameter),
        QueryParameters.builder().bind(lowerParameter, lower).bind(upperParameter, upper).build());
  }

  static <V> FrameworkQueryCondition betweenParameters(
      Selectable<V> value, QueryParameter<V> lower, QueryParameter<V> upper) {
    return new FrameworkQueryCondition(
        new BetweenNode<>(value, lower, upper), QueryParameters.empty());
  }

  static <V> FrameworkQueryCondition like(Selectable<V> value, V pattern) {
    QueryParameter<V> parameter = QueryParameter.anonymousNonNull(value.javaType());
    return new FrameworkQueryCondition(
        new LikeNode<>(value, parameter), QueryParameters.of(parameter, pattern));
  }

  static <V> FrameworkQueryCondition likeParameter(Selectable<V> value, QueryParameter<V> pattern) {
    return new FrameworkQueryCondition(new LikeNode<>(value, pattern), QueryParameters.empty());
  }

  static <V> FrameworkQueryCondition membership(
      Selectable<V> value, List<V> candidates, boolean negated) {
    QueryParameters.Builder parameters = QueryParameters.builder();
    List<QueryParameter<V>> references = new ArrayList<>(candidates.size());
    for (V candidate : candidates) {
      QueryParameter<V> parameter = QueryParameter.anonymousNonNull(value.javaType());
      references.add(parameter);
      parameters.bind(parameter, candidate);
    }
    return new FrameworkQueryCondition(
        new InNode<>(value, references, negated), parameters.build());
  }

  static <V> FrameworkQueryCondition membershipParameters(
      Selectable<V> value, List<QueryParameter<V>> candidates, boolean negated) {
    return new FrameworkQueryCondition(
        new InNode<>(value, candidates, negated), QueryParameters.empty());
  }

  static QueryCondition logical(
      LogicalOperator operator, QueryCondition left, QueryCondition right) {
    FrameworkQueryCondition leftCondition = requireFramework(left);
    FrameworkQueryCondition rightCondition = requireFramework(right);
    return new FrameworkQueryCondition(
        new LogicalNode(operator, leftCondition.root, rightCondition.root),
        leftCondition.parameters.merge(rightCondition.parameters));
  }

  static QueryCondition negate(QueryCondition operand) {
    FrameworkQueryCondition condition = requireFramework(operand);
    return new FrameworkQueryCondition(new NotNode(condition.root), condition.parameters);
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
    QueryConditionCompiler target = Objects.requireNonNull(compiler, "compiler");
    target.include(parameters);
    return root.compile(target);
  }

  FrameworkQueryCondition structureOnly() {
    return parameters.isEmpty() ? this : new FrameworkQueryCondition(root, QueryParameters.empty());
  }

  QueryParameters parameters() {
    return parameters;
  }

  private static FrameworkQueryCondition requireFramework(QueryCondition condition) {
    return (FrameworkQueryCondition) Objects.requireNonNull(condition, "condition");
  }

  private sealed interface Node
      permits ValueComparisonNode,
          ExpressionComparisonNode,
          NullNode,
          BetweenNode,
          LikeNode,
          InNode,
          LogicalNode,
          NotNode {

    SqlPredicate compile(QueryConditionCompiler compiler);
  }

  private record ValueComparisonNode<V>(
      Selectable<V> left, ComparisonOperator operator, QueryParameter<V> parameter)
      implements Node {

    private ValueComparisonNode {
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(parameter, "parameter");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new ComparisonPredicate<>(
          left.expression(), operator, compiler.parameter(left, parameter));
    }
  }

  private record ExpressionComparisonNode<V>(
      Selectable<V> left, ComparisonOperator operator, Selectable<V> right) implements Node {

    private ExpressionComparisonNode {
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(right, "right");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new ComparisonPredicate<>(left.expression(), operator, right.expression());
    }
  }

  private record NullNode(Selectable<?> selectable, NullOperator operator) implements Node {

    private NullNode {
      Objects.requireNonNull(selectable, "selectable");
      Objects.requireNonNull(operator, "operator");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new NullPredicate(selectable.expression(), operator);
    }
  }

  private record BetweenNode<V>(
      Selectable<V> value, QueryParameter<V> lower, QueryParameter<V> upper) implements Node {

    private BetweenNode {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(lower, "lower");
      Objects.requireNonNull(upper, "upper");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new BetweenPredicate<>(
          value.expression(), compiler.parameter(value, lower), compiler.parameter(value, upper));
    }
  }

  private record LikeNode<V>(Selectable<V> value, QueryParameter<V> pattern) implements Node {

    private LikeNode {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(pattern, "pattern");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new LikePredicate(value.expression(), compiler.parameter(value, pattern));
    }
  }

  private record InNode<V>(Selectable<V> value, List<QueryParameter<V>> candidates, boolean negated)
      implements Node {

    private InNode {
      Objects.requireNonNull(value, "value");
      candidates = List.copyOf(candidates);
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      List<ParameterSlot<V>> slots = new ArrayList<>(candidates.size());
      for (QueryParameter<V> candidate : candidates) {
        slots.add(compiler.parameter(value, candidate));
      }
      return new InPredicate<>(value.expression(), slots, negated);
    }
  }

  private record LogicalNode(LogicalOperator operator, Node left, Node right) implements Node {

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

  private record NotNode(Node operand) implements Node {

    private NotNode {
      Objects.requireNonNull(operand, "operand");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new NotPredicate(operand.compile(compiler));
    }
  }
}

/** Package-local bridge that keeps AST compilation off the public condition contract. */
final class QueryConditions {

  private QueryConditions() {}

  static SqlPredicate compile(QueryCondition condition, QueryConditionCompiler compiler) {
    Objects.requireNonNull(condition, "condition");
    return ((FrameworkQueryCondition) condition)
        .compile(Objects.requireNonNull(compiler, "compiler"));
  }

  static QueryCondition structure(QueryCondition condition) {
    return framework(condition).structureOnly();
  }

  static QueryParameters parameters(QueryCondition condition) {
    return framework(condition).parameters();
  }

  static QueryCondition reusableStructure(QueryCondition condition) {
    FrameworkQueryCondition framework = framework(condition);
    if (!framework.parameters().isEmpty()) {
      throw new QueryValidationException(
          "a reusable SELECT description cannot capture ordinary values; use Sql.parameter(...) "
              + "in the condition and bind it through "
              + "executor.query(description, QueryParameters)");
    }
    return framework.structureOnly();
  }

  private static FrameworkQueryCondition framework(QueryCondition condition) {
    return (FrameworkQueryCondition) Objects.requireNonNull(condition, "condition");
  }
}

/** One query-block compiler backed by the statement layout shared across SQL clauses. */
final class QueryConditionCompiler {

  private final StatementParameterLayout layout;
  private final QueryParameterBindings bindings;
  private final StatementParameterLayout.QueryBlock queryBlock;

  QueryConditionCompiler() {
    this(new StatementParameterLayout(), new QueryParameterBindings());
  }

  QueryConditionCompiler(StatementParameterLayout layout, QueryParameterBindings bindings) {
    this.layout = Objects.requireNonNull(layout, "layout");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.queryBlock = layout.newQueryBlock();
  }

  <V> ParameterSlot<V> parameter(Selectable<V> source, QueryParameter<V> parameter) {
    return queryBlock.parameter(source, parameter);
  }

  void include(QueryParameters included) {
    bindings.include(included);
  }

  List<Selectable<?>> parameterSources() {
    return layout.parameterSources();
  }

  List<QueryParameter<?>> parameterReferences() {
    return layout.parameterReferences();
  }

  List<ParameterSlot<?>> parameterSlots() {
    return layout.parameterSlots();
  }

  QueryParameters parameters() {
    return bindings.parameters();
  }
}
