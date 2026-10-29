package io.skis.query.internal;

import io.skis.query.NonNullSelectable;
import io.skis.query.QueryCondition;
import io.skis.query.QueryParameter;
import io.skis.query.Selectable;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Value-separated standard expression with query-block compilation and a lazy structural preview.
 */
sealed class ExpressionSelectable<V> implements Selectable<V> permits NonNullExpressionSelectable {

  private final SqlExpression<V> descriptor;
  private final @Nullable Recipe<V> recipe;
  private final boolean forwardsMapping;
  private volatile @Nullable Preview<V> preview;

  ExpressionSelectable(SqlExpression<V> expression) {
    this.descriptor = Objects.requireNonNull(expression, "expression");
    this.recipe = null;
    this.forwardsMapping = false;
    this.preview = new Preview<>(expression, List.of());
  }

  ExpressionSelectable(SqlExpression<V> descriptor, Recipe<V> recipe) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.recipe = Objects.requireNonNull(recipe, "recipe");
    this.forwardsMapping = false;
  }

  /** A scope leaf forwards Codec provenance, without compiling its shape template as SQL input. */
  ExpressionSelectable(SqlExpression<V> descriptor, Selectable<V> source) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(source, "source");
    if (!descriptor.javaType().equals(source.javaType())
        || descriptor.sqlType() != source.sqlType())
      throw new IllegalArgumentException("mapped scope leaf must preserve its Codec source type");
    this.recipe =
        new Recipe<>(
            (compiler, operands, condition) -> descriptor,
            List.of(Objects.requireNonNull(source, "source")),
            null,
            QueryParameters.empty());
    this.forwardsMapping = true;
    this.preview = new Preview<>(descriptor, List.of());
  }

  final boolean forwardsMapping() {
    return forwardsMapping;
  }

  @Override
  public final Class<V> javaType() {
    return descriptor.javaType();
  }

  @Override
  public final SqlType sqlType() {
    return descriptor.sqlType();
  }

  @Override
  public final Nullability nullability() {
    return descriptor.nullability();
  }

  final SqlExpression<V> expression() {
    return preview().expression();
  }

  final List<QueryParameter<?>> parameterReferences() {
    return preview().parameters();
  }

  final boolean capturesValues() {
    return !forwardsMapping && recipe != null && recipe.capturesValues();
  }

  final boolean decomposed() {
    return recipe == null;
  }

  /** Capture changes value ownership, while Codec provenance keeps the same descriptor identity. */
  final Object codecIdentity() {
    return descriptor;
  }

  /** Value provenance excludes CASE conditions and ordinary COUNT's input. */
  final List<Selectable<?>> valueSources() {
    if (descriptor instanceof AggregateExpression<?> aggregate
        && aggregate.kind() == AggregateExpression.Kind.COUNT
        && !aggregate.distinct()) {
      return List.of();
    }
    return recipe == null ? List.of() : recipe.operands();
  }

  /** Finds a grouping witness without compiling or binding an expression that will be discarded. */
  final @Nullable AggregateExpression<?> localAggregate(IdentityHashMap<Object, Boolean> visited) {
    if (forwardsMapping) return null;
    if (visited.put(this, Boolean.TRUE) != null) {
      return null;
    }
    if (descriptor instanceof AggregateExpression<?> aggregate) {
      return aggregate;
    }
    Recipe<V> current = recipe;
    if (current != null) {
      if (current.condition() != null) {
        AggregateExpression<?> aggregate =
            QueryConditions.framework(current.condition()).localAggregate(visited);
        if (aggregate != null) {
          return aggregate;
        }
      }
      for (Selectable<?> operand : current.operands()) {
        if (operand instanceof ExpressionSelectable<?> expression) {
          AggregateExpression<?> aggregate = expression.localAggregate(visited);
          if (aggregate != null) {
            return aggregate;
          }
        }
      }
    }
    return null;
  }

  /** Shares detached compilation across capture boundaries without retaining a value owner. */
  final Object compilationIdentity() {
    // Every composed expression owns its descriptor, and capture preserves that exact object.
    // Value-bearing previews keep their own identity so memo hits cannot skip binding fragments.
    return capturesValues() ? this : descriptor;
  }

  final boolean containsSubquery() {
    if (forwardsMapping) return false;
    // Legacy raw wrappers are conservative except for known scalar leaves and COUNT(*).
    return recipe != null
        ? recipe.containsSubquery()
        : !(descriptor instanceof LiteralExpression<?>
            || descriptor instanceof ParameterSlot<?>
            || (descriptor instanceof AggregateExpression<?> aggregate
                && aggregate.operand().isEmpty()));
  }

  final SqlExpression<V> compile(QueryConditionCompiler compiler) {
    return forwardsMapping || recipe == null ? descriptor : recipe.compile(compiler);
  }

  final ExpressionSelectable<V> capture(ExpressionCapture capture) {
    if (forwardsMapping) return this;
    Recipe<V> current = recipe;
    if (current == null || !current.capturesValues()) {
      return this;
    }
    capture.include(current.parameters());
    List<Selectable<?>> operands =
        current.operands().stream().<Selectable<?>>map(capture::selectable).toList();
    QueryCondition condition =
        current.condition() == null ? null : capture.condition(current.condition());
    Recipe<V> replacement =
        new Recipe<>(current.factory(), operands, condition, QueryParameters.empty());
    return this instanceof NonNullExpressionSelectable<?>
        ? new NonNullExpressionSelectable<>(descriptor, replacement)
        : new ExpressionSelectable<>(descriptor, replacement);
  }

  private Preview<V> preview() {
    Preview<V> existing = preview;
    if (existing == null) {
      synchronized (this) {
        existing = preview;
        if (existing == null) {
          StatementParameterLayout layout = new StatementParameterLayout();
          existing =
              new Preview<>(
                  compile(new QueryConditionCompiler(layout, new QueryParameterBindings())),
                  layout.parameterReferences());
          preview = existing;
        }
      }
    }
    return existing;
  }

  private record Preview<V>(SqlExpression<V> expression, List<QueryParameter<?>> parameters) {}

  @FunctionalInterface
  interface AstFactory<V> {
    SqlExpression<V> create(
        @Nullable QueryConditionCompiler compiler,
        List<SqlExpression<?>> operands,
        @Nullable SqlPredicate condition);
  }

  /** Shares operands and value fragments; it never flattens a subtree while composing it. */
  record Recipe<V>(
      AstFactory<V> factory,
      List<Selectable<?>> operands,
      @Nullable QueryCondition condition,
      QueryParameters parameters,
      boolean capturesValues,
      boolean containsSubquery) {

    Recipe(
        AstFactory<V> factory,
        List<Selectable<?>> operands,
        @Nullable QueryCondition condition,
        QueryParameters parameters) {
      this(
          factory,
          List.copyOf(operands),
          condition,
          parameters,
          !parameters.isEmpty()
              || operands.stream().anyMatch(ExpressionCapture::capturesValues)
              || (condition != null && QueryConditions.framework(condition).capturesValues()),
          operands.stream().anyMatch(ExpressionCapture::containsSubquery)
              || (condition != null && QueryConditions.framework(condition).containsSubquery()));
    }

    SqlExpression<V> compile(QueryConditionCompiler compiler) {
      compiler.include(parameters);
      SqlPredicate predicate =
          condition == null ? null : QueryConditions.compile(condition, compiler);
      List<SqlExpression<?>> expressions =
          operands.stream().<SqlExpression<?>>map(compiler::expression).toList();
      return factory.create(compiler, expressions, predicate);
    }
  }
}

/** Package-owned wrapper whose AST has a declared non-null result. */
final class NonNullExpressionSelectable<V> extends ExpressionSelectable<V>
    implements NonNullSelectable<V> {

  NonNullExpressionSelectable(SqlExpression<V> expression) {
    super(expression);
    if (expression.nullability().isNullable()) {
      throw new IllegalArgumentException("non-null selectable uses a nullable SQL expression");
    }
  }

  NonNullExpressionSelectable(SqlExpression<V> descriptor, Recipe<V> recipe) {
    super(descriptor, recipe);
    if (descriptor.nullable()) {
      throw new IllegalArgumentException("non-null selectable uses a nullable SQL expression");
    }
  }

  NonNullExpressionSelectable(SqlExpression<V> descriptor, Selectable<V> source) {
    super(descriptor, source);
    if (descriptor.nullable())
      throw new IllegalArgumentException("non-null selectable uses a nullable SQL expression");
  }
}
