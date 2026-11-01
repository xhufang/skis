package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.NullPlacement;
import io.skis.query.QueryCondition;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Shared validation and construction rules for columns and other framework-owned expressions. */
public final class SelectableSupport {

  private SelectableSupport() {}

  static <S extends Selectable<?>> S requireFramework(S selectable) {
    Objects.requireNonNull(selectable, "selectable");
    if (selectable instanceof QueryColumn<?, ?>
        || selectable instanceof DerivedColumnSelectable<?>
        || selectable instanceof ExpressionSelectable<?>
        || selectable instanceof ScalarSubquerySelectable<?>) {
      return selectable;
    }
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE, "selectable was not created by SKIS");
  }

  @SuppressWarnings("unchecked")
  static <V> SqlExpression<V> expression(Selectable<V> selectable) {
    Selectable<?> source = Objects.requireNonNull(selectable, "selectable");
    SqlExpression<?> expression =
        switch (source) {
          case QueryColumn<?, ?> column -> column.expression();
          case DerivedColumnSelectable<?> column -> column.expression();
          case ExpressionSelectable<?> standard -> standard.expression();
          case ScalarSubquerySelectable<?> scalar -> scalar.expression();
          default ->
              throw QueryValidationException.of(
                  SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE, "selectable was not created by SKIS");
        };
    return (SqlExpression<V>) expression;
  }

  public static <V> QueryCondition valueComparison(
      Selectable<V> selectable, ComparisonOperator operator, @Nullable V value) {
    Selectable<V> source = requireFramework(selectable);
    requireComparison(source, operator);
    return FrameworkQueryCondition.valueComparison(
        source, operator, requireValue(source, operator.name(), value));
  }

  public static <V> QueryCondition expressionComparison(
      Selectable<V> left, ComparisonOperator operator, Selectable<V> right) {
    Selectable<V> checkedLeft = requireFramework(left);
    Selectable<V> checkedRight = requireFramework(right);
    requireCompatible(checkedLeft, operator, checkedRight);
    return FrameworkQueryCondition.expressionComparison(checkedLeft, operator, checkedRight);
  }

  public static QueryCondition nullCheck(Selectable<?> selectable, NullOperator operator) {
    return FrameworkQueryCondition.nullCheck(
        requireFramework(selectable), Objects.requireNonNull(operator, "operator"));
  }

  public static <V> QueryCondition between(
      Selectable<V> selectable, @Nullable V lower, @Nullable V upper) {
    Selectable<V> source = requireFramework(selectable);
    requireOrdering(source, "between");
    return FrameworkQueryCondition.between(
        source,
        requireValue(source, "between lower bound", lower),
        requireValue(source, "between upper bound", upper));
  }

  public static QueryCondition like(Selectable<?> selectable, String pattern) {
    Selectable<?> source = requireFramework(selectable);
    requireLike(source);
    return likeString(source, Objects.requireNonNull(pattern, "pattern"));
  }

  @SuppressWarnings("unchecked")
  private static QueryCondition likeString(Selectable<?> selectable, String pattern) {
    return FrameworkQueryCondition.like((Selectable<String>) selectable, pattern);
  }

  public static <V> QueryCondition membership(
      Selectable<V> selectable, Collection<? extends V> values, boolean negated) {
    Selectable<V> source = requireFramework(selectable);
    List<V> copy = copyValues(source, values, negated);
    return FrameworkQueryCondition.membership(source, copy, negated);
  }

  public static <V> QueryCondition subqueryMembership(
      Selectable<V> selectable,
      SingleColumnQuery<? extends @Nullable V> subquery,
      boolean negated) {
    Selectable<V> source = requireFramework(selectable);
    SingleColumnQuery<? extends @Nullable V> child = Objects.requireNonNull(subquery, "subquery");
    Class<?> leftJavaType = boxedJavaType(source.javaType());
    Class<?> outputJavaType =
        boxedJavaType(
            SelectableSupport.expression(
                    QueryModels.description(child).state.selected().singleSelectable())
                .javaType());
    if (!leftJavaType.equals(outputJavaType)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          "expression '"
              + summary(source)
              + "' has Java type "
              + leftJavaType.getTypeName()
              + " but IN subquery produces "
              + outputJavaType.getTypeName());
    }
    if (!source
        .sqlType()
        .equalityCompatibleWith(
            SelectableSupport.expression(
                    QueryModels.description(child).state.selected().singleSelectable())
                .sqlType())) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          (negated ? "notIn" : "in")
              + " subquery does not support SQL types "
              + source.sqlType()
              + " and "
              + SelectableSupport.expression(
                      QueryModels.description(child).state.selected().singleSelectable())
                  .sqlType());
    }
    return FrameworkQueryCondition.subqueryMembership(
        source, QueryModels.description(child), negated);
  }

  public static SortSpecification order(Selectable<?> selectable, SortDirection direction) {
    Selectable<?> source = requireFramework(selectable);
    requireOrdering(source, direction == SortDirection.ASC ? "asc" : "desc");
    return new DefaultSortSpecification(source, direction, NullPlacement.DIALECT_DEFAULT);
  }

  static String summary(Selectable<?> selectable) {
    Objects.requireNonNull(selectable, "selectable");
    if (selectable instanceof QueryColumn<?, ?> column) {
      String qualifier = column.table().alias().orElse(column.table().entity().table().name());
      return qualifier + '.' + column.property().name();
    }
    if (selectable instanceof DerivedColumnSelectable<?> column) {
      return column.relation().alias() + '.' + column.output().alias();
    }
    return expression(selectable).getClass().getSimpleName();
  }

  static boolean sameOccurrence(Selectable<?> left, Selectable<?> right) {
    return switch (left) {
      case QueryColumn<?, ?> leftColumn when right instanceof QueryColumn<?, ?> rightColumn ->
          leftColumn.table() == rightColumn.table()
              && leftColumn.property() == rightColumn.property();
      case DerivedColumnSelectable<?> leftColumn
          when right instanceof DerivedColumnSelectable<?> rightColumn ->
          QueryModels.relation(leftColumn.relation()).reference()
                  == QueryModels.relation(rightColumn.relation()).reference()
              && leftColumn.output() == rightColumn.output();
      case ScalarSubquerySelectable<?> leftScalar
          when right instanceof ScalarSubquerySelectable<?> rightScalar ->
          expressionIdentity(leftScalar).equals(expressionIdentity(rightScalar));
      case ExpressionSelectable<?> leftExpression
          when right instanceof ExpressionSelectable<?> rightExpression ->
          leftExpression.outputAlias() != null || rightExpression.outputAlias() != null
              ? leftExpression.compilationIdentity() == rightExpression.compilationIdentity()
              : expressionIdentity(leftExpression).equals(expressionIdentity(rightExpression));
      default -> left == right;
    };
  }

  static ExpressionIdentity expressionIdentity(Selectable<?> selectable) {
    return new ExpressionIdentity(
        expression(selectable),
        selectable instanceof ScalarSubquerySelectable<?> scalar
            ? scalar.parameterReferences()
            : selectable instanceof ExpressionSelectable<?> standard
                ? standard.parameterReferences()
                : List.of());
  }

  /** Local expression equality retains query references erased by standalone JDBC slot layout. */
  record ExpressionIdentity(SqlExpression<?> expression, List<QueryParameter<?>> parameters) {

    ExpressionIdentity {
      Objects.requireNonNull(expression, "expression");
      parameters = List.copyOf(parameters);
    }
  }

  private static <V> List<V> copyValues(
      Selectable<V> selectable, Collection<? extends V> values, boolean negated) {
    Objects.requireNonNull(values, "values");
    if (selectable.sqlType() == SqlType.OTHER) {
      throw unsupported(selectable, negated ? "notIn" : "in");
    }
    List<V> copy = new ArrayList<>(values.size());
    int index = 0;
    for (V value : values) {
      copy.add(requireValue(selectable, (negated ? "notIn" : "in") + " value " + index, value));
      index++;
    }
    return List.copyOf(copy);
  }

  private static void requireComparison(Selectable<?> selectable, ComparisonOperator operator) {
    Objects.requireNonNull(operator, "operator");
    if (operator.isOrdered()) {
      requireOrdering(selectable, operator.name());
    } else if (selectable.sqlType() == SqlType.OTHER) {
      throw unsupported(selectable, operator.name());
    }
  }

  private static void requireCompatible(
      Selectable<?> left, ComparisonOperator operator, Selectable<?> right) {
    requireComparison(left, operator);
    if (!left.javaType().equals(right.javaType())) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          "expression '"
              + summary(left)
              + "' has Java type "
              + left.javaType().getTypeName()
              + " but expression '"
              + summary(right)
              + "' has Java type "
              + right.javaType().getTypeName());
    }
    boolean compatible =
        operator.isOrdered()
            ? left.sqlType().orderingCompatibleWith(right.sqlType())
            : left.sqlType().equalityCompatibleWith(right.sqlType());
    if (!compatible) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          operator + " does not support SQL types " + left.sqlType() + " and " + right.sqlType());
    }
  }

  private static void requireOrdering(Selectable<?> selectable, String operation) {
    if (!selectable.sqlType().isOrderable()) {
      throw unsupported(selectable, operation);
    }
  }

  private static void requireLike(Selectable<?> selectable) {
    if (selectable.javaType() != String.class || !selectable.sqlType().supportsLike()) {
      throw unsupported(selectable, "like");
    }
  }

  private static <V> V requireValue(
      Selectable<V> selectable, String operation, @Nullable Object value) {
    if (value == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          operation
              + "(null) is not supported for expression '"
              + summary(selectable)
              + "'; use isNull()/isNotNull() for SQL null tests");
    }
    if (!selectable.javaType().isInstance(value)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          "expression '"
              + summary(selectable)
              + "' requires "
              + selectable.javaType().getTypeName()
              + " but received "
              + value.getClass().getTypeName());
    }
    return selectable.javaType().cast(value);
  }

  private static QueryValidationException unsupported(Selectable<?> selectable, String operation) {
    return QueryValidationException.of(
        SkisErrorCode.QUERY_TYPE_MISMATCH,
        operation
            + " is not supported for expression '"
            + summary(selectable)
            + "' with SQL type "
            + selectable.sqlType());
  }

  private static Class<?> boxedJavaType(Class<?> javaType) {
    Objects.requireNonNull(javaType, "javaType");
    if (!javaType.isPrimitive()) {
      return javaType;
    }
    return switch (javaType.getName()) {
      case "boolean" -> Boolean.class;
      case "byte" -> Byte.class;
      case "short" -> Short.class;
      case "int" -> Integer.class;
      case "long" -> Long.class;
      case "float" -> Float.class;
      case "double" -> Double.class;
      case "char" -> Character.class;
      default ->
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE, "IN subquery Java type must not be void");
    };
  }
}
