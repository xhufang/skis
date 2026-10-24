package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.QueryCondition;
import io.skis.query.QueryParameter;
import io.skis.query.SelectDescription;
import io.skis.query.Selectable;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The single condition implementation used by both physical columns and standard expressions. */
final class FrameworkQueryCondition implements QueryCondition {

  private final Node root;
  private final QueryParameters parameters;
  private final boolean capturesValues;
  private final boolean containsSubquery;

  private FrameworkQueryCondition(Node root, QueryParameters parameters) {
    this.root = Objects.requireNonNull(root, "root");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    this.capturesValues = !parameters.isEmpty() || capturesValues(root);
    this.containsSubquery = containsSubquery(root);
  }

  static <V> FrameworkQueryCondition valueComparison(
      Selectable<V> left, ComparisonOperator operator, V value) {
    QueryParameter<V> parameter = DefaultQueryParameter.anonymousNonNull(left.javaType());
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
    QueryParameter<V> lowerParameter = DefaultQueryParameter.anonymousNonNull(value.javaType());
    QueryParameter<V> upperParameter = DefaultQueryParameter.anonymousNonNull(value.javaType());
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
    QueryParameter<V> parameter = DefaultQueryParameter.anonymousNonNull(value.javaType());
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
      QueryParameter<V> parameter = DefaultQueryParameter.anonymousNonNull(value.javaType());
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

  static <V> FrameworkQueryCondition subqueryMembership(
      Selectable<V> value, SelectQueryState<V> subquery, boolean negated) {
    return new FrameworkQueryCondition(
        new InSubqueryNode<>(value, subquery, negated), QueryParameters.empty());
  }

  static FrameworkQueryCondition exists(SelectDescription<?> description, boolean negated) {
    return new FrameworkQueryCondition(
        new ExistsNode(
            QueryModels.description(Objects.requireNonNull(description, "description")).state(),
            negated),
        QueryParameters.empty());
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
    return (FrameworkQueryCondition) new ExpressionCapture().condition(this);
  }

  boolean capturesValues() {
    return capturesValues;
  }

  boolean capturesExpressionValues() {
    return capturesValues(root);
  }

  boolean containsSubquery() {
    return containsSubquery;
  }

  @Nullable AggregateExpression<?> localAggregate(IdentityHashMap<Object, Boolean> visited) {
    return localAggregate(root, visited);
  }

  private static @Nullable AggregateExpression<?> localAggregate(Selectable<?> selectable) {
    return selectable instanceof ExpressionSelectable<?> expression
        ? expression.localAggregate(new IdentityHashMap<>())
        : null;
  }

  private static @Nullable AggregateExpression<?> localAggregate(
      Selectable<?> selectable, IdentityHashMap<Object, Boolean> visited) {
    return selectable instanceof ExpressionSelectable<?> expression
        ? expression.localAggregate(visited)
        : null;
  }

  private static @Nullable AggregateExpression<?> localAggregate(
      Node node, IdentityHashMap<Object, Boolean> visited) {
    if (visited.put(node, Boolean.TRUE) != null) {
      return null;
    }
    return switch (node) {
      case ValueComparisonNode<?> value -> localAggregate(value.left(), visited);
      case ExpressionComparisonNode<?> value -> {
        AggregateExpression<?> first = localAggregate(value.left(), visited);
        yield first != null ? first : localAggregate(value.right(), visited);
      }
      case NullNode value -> localAggregate(value.selectable(), visited);
      case BetweenNode<?> value -> localAggregate(value.value(), visited);
      case LikeNode<?> value -> localAggregate(value.value(), visited);
      case InNode<?> value -> localAggregate(value.value(), visited);
      case InSubqueryNode<?> value -> localAggregate(value.value(), visited);
      case LogicalNode value -> {
        AggregateExpression<?> first = localAggregate(value.left(), visited);
        yield first != null ? first : localAggregate(value.right(), visited);
      }
      case NotNode value -> localAggregate(value.operand(), visited);
      case ExistsNode ignored -> null;
    };
  }

  private static boolean containsSubquery(Node node) {
    return switch (node) {
      case ValueComparisonNode<?> value -> ExpressionCapture.containsSubquery(value.left());
      case ExpressionComparisonNode<?> comparison ->
          ExpressionCapture.containsSubquery(comparison.left())
              || ExpressionCapture.containsSubquery(comparison.right());
      case NullNode value -> ExpressionCapture.containsSubquery(value.selectable());
      case BetweenNode<?> value -> ExpressionCapture.containsSubquery(value.value());
      case LikeNode<?> value -> ExpressionCapture.containsSubquery(value.value());
      case InNode<?> value -> ExpressionCapture.containsSubquery(value.value());
      case LogicalNode logical -> logical.containsSubquery();
      case NotNode not -> not.containsSubquery();
      case InSubqueryNode<?> ignored -> true;
      case ExistsNode ignored -> true;
    };
  }

  FrameworkQueryCondition capture(ExpressionCapture capture) {
    capture.include(parameters);
    return new FrameworkQueryCondition(captureNode(root, capture), QueryParameters.empty());
  }

  private static boolean capturesValues(Node node) {
    return switch (node) {
      case ValueComparisonNode<?> value -> ExpressionCapture.capturesValues(value.left());
      case ExpressionComparisonNode<?> comparison ->
          ExpressionCapture.capturesValues(comparison.left())
              || ExpressionCapture.capturesValues(comparison.right());
      case NullNode value -> ExpressionCapture.capturesValues(value.selectable());
      case BetweenNode<?> value -> ExpressionCapture.capturesValues(value.value());
      case LikeNode<?> value -> ExpressionCapture.capturesValues(value.value());
      case InNode<?> value -> ExpressionCapture.capturesValues(value.value());
      case LogicalNode logical -> logical.capturesValues();
      case NotNode not -> not.capturesValues();
      case InSubqueryNode<?> value -> ExpressionCapture.capturesValues(value.value());
      case ExistsNode ignored -> false;
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Node captureNode(Node node, ExpressionCapture capture) {
    if (!capturesValues(node)) {
      return node;
    }
    return switch (node) {
      case ValueComparisonNode value ->
          new ValueComparisonNode(
              capture.selectable(value.left()), value.operator(), value.parameter());
      case ExpressionComparisonNode comparison ->
          new ExpressionComparisonNode(
              capture.selectable(comparison.left()),
              comparison.operator(),
              capture.selectable(comparison.right()));
      case NullNode value -> new NullNode(capture.selectable(value.selectable()), value.operator());
      case BetweenNode value ->
          new BetweenNode(capture.selectable(value.value()), value.lower(), value.upper());
      case LikeNode value -> new LikeNode(capture.selectable(value.value()), value.pattern());
      case InNode value ->
          new InNode(capture.selectable(value.value()), value.candidates(), value.negated());
      case LogicalNode logical ->
          new LogicalNode(
              logical.operator(),
              captureNode(logical.left(), capture),
              captureNode(logical.right(), capture));
      case NotNode not -> new NotNode(captureNode(not.operand(), capture));
      case InSubqueryNode value ->
          new InSubqueryNode(capture.selectable(value.value()), value.subquery(), value.negated());
      case ExistsNode exists -> exists;
    };
  }

  private static FrameworkQueryCondition requireFramework(QueryCondition condition) {
    return QueryConditions.framework(condition);
  }

  private sealed interface Node
      permits ValueComparisonNode,
          ExpressionComparisonNode,
          NullNode,
          BetweenNode,
          LikeNode,
          InNode,
          InSubqueryNode,
          ExistsNode,
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
      SqlExpression<V> expression = compiler.expression(left);
      return new ComparisonPredicate<>(expression, operator, compiler.parameter(left, parameter));
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
      return new ComparisonPredicate<>(
          compiler.expression(left), operator, compiler.expression(right));
    }
  }

  private record NullNode(Selectable<?> selectable, NullOperator operator) implements Node {

    private NullNode {
      Objects.requireNonNull(selectable, "selectable");
      Objects.requireNonNull(operator, "operator");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new NullPredicate(compiler.expression(selectable), operator);
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
          compiler.expression(value),
          compiler.parameter(value, lower),
          compiler.parameter(value, upper));
    }
  }

  private record LikeNode<V>(Selectable<V> value, QueryParameter<V> pattern) implements Node {

    private LikeNode {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(pattern, "pattern");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new LikePredicate(compiler.expression(value), compiler.parameter(value, pattern));
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
      if (candidates.isEmpty() && compiler.releaseOriginalExpressions()) {
        AggregateExpression<?> aggregate = localAggregate(value);
        if (aggregate != null) {
          return aggregate.emptyMembership(negated);
        }
        return new InPredicate<>(LiteralExpression.trueLiteral(), List.of(), negated);
      }
      SqlExpression<V> expression = compiler.expression(value);
      if (candidates.isEmpty()) {
        compiler.recordEmptyMembership();
      }
      List<ParameterSlot<V>> slots = new ArrayList<>(candidates.size());
      for (QueryParameter<V> candidate : candidates) {
        slots.add(compiler.parameter(value, candidate));
      }
      return new InPredicate<>(expression, slots, negated);
    }
  }

  private record InSubqueryNode<V>(
      Selectable<V> value, SelectQueryState<V> subquery, boolean negated) implements Node {

    private InSubqueryNode {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(subquery, "subquery");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new InSubqueryPredicate<>(
          compiler.expression(value), compiler.subquery(subquery), negated);
    }
  }

  private record ExistsNode(SelectQueryState<?> subquery, boolean negated) implements Node {

    private ExistsNode {
      Objects.requireNonNull(subquery, "subquery");
    }

    @Override
    public SqlPredicate compile(QueryConditionCompiler compiler) {
      return new ExistsPredicate(compiler.subquery(subquery), negated);
    }
  }

  private record LogicalNode(
      LogicalOperator operator,
      Node left,
      Node right,
      boolean capturesValues,
      boolean containsSubquery)
      implements Node {

    private LogicalNode(LogicalOperator operator, Node left, Node right) {
      this(
          operator,
          left,
          right,
          FrameworkQueryCondition.capturesValues(left)
              || FrameworkQueryCondition.capturesValues(right),
          FrameworkQueryCondition.containsSubquery(left)
              || FrameworkQueryCondition.containsSubquery(right));
    }

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

  private record NotNode(Node operand, boolean capturesValues, boolean containsSubquery)
      implements Node {

    private NotNode(Node operand) {
      this(
          operand,
          FrameworkQueryCondition.capturesValues(operand),
          FrameworkQueryCondition.containsSubquery(operand));
    }

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
    return framework(condition).compile(Objects.requireNonNull(compiler, "compiler"));
  }

  static QueryCondition reusableStructure(QueryCondition condition) {
    FrameworkQueryCondition framework = framework(condition);
    if (framework.capturesValues()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_CAPTURED_VALUE,
          "a reusable SELECT description cannot capture ordinary values; use Sql.parameter(...) "
              + "in the condition and bind it through "
              + "executor.query(description).bind(parameter, value)");
    }
    return framework.structureOnly();
  }

  static FrameworkQueryCondition framework(QueryCondition condition) {
    if (Objects.requireNonNull(condition, "condition")
        instanceof FrameworkQueryCondition framework) {
      return framework;
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE, "condition was not created by SKIS");
  }
}

/** One query-block compiler backed by the statement layout shared across SQL clauses. */
final class QueryConditionCompiler {

  private final StatementParameterLayout layout;
  private final QueryParameterBindings bindings;
  private final StatementParameterLayout.QueryBlock queryBlock;
  private @Nullable IdentityHashMap<Object, SqlExpression<?>> expressions;

  QueryConditionCompiler(StatementParameterLayout layout, QueryParameterBindings bindings) {
    this.layout = Objects.requireNonNull(layout, "layout");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.queryBlock = layout.newQueryBlock();
  }

  <V> ParameterSlot<V> parameter(Selectable<V> source, QueryParameter<V> parameter) {
    return queryBlock.parameter(source, parameter);
  }

  boolean releaseOriginalExpressions() {
    return !layout.retainOriginalExpressions();
  }

  void recordSelectedOrdering() {
    layout.recordSelectedOrdering();
  }

  void recordEmptyMembership() {
    layout.recordEmptyMembership();
  }

  <V> SqlExpression<V> expression(Selectable<V> selectable) {
    Objects.requireNonNull(selectable, "selectable");
    if (selectable instanceof ScalarSubquerySelectable<?> scalar) {
      return compileScalar(scalar);
    }
    if (selectable instanceof ExpressionSelectable<V> standard) {
      if (!standard.composed()) {
        return standard.expression();
      }
      if (standard.containsSubquery()) {
        return standard.compile(this);
      }
      IdentityHashMap<Object, SqlExpression<?>> memo = expressions;
      if (memo == null) {
        memo = new IdentityHashMap<>();
        expressions = memo;
      }
      Object identity = standard.compilationIdentity();
      SqlExpression<?> existing = memo.get(identity);
      if (existing == null) {
        existing = standard.compile(this);
        memo.put(identity, existing);
      }
      @SuppressWarnings("unchecked")
      SqlExpression<V> typed = (SqlExpression<V>) existing;
      return typed;
    }
    return SelectableSupport.expression(selectable);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private <V> SqlExpression<V> compileScalar(ScalarSubquerySelectable<?> scalar) {
    return ((ScalarSubquerySelectable) scalar).compile(this);
  }

  void include(QueryParameters included) {
    bindings.include(included);
  }

  SelectStatement subquery(SelectQueryState<?> state) {
    return QueryStructureCompiler.compileSubquery(
        Objects.requireNonNull(state, "state"), layout, bindings);
  }
}
