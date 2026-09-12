package io.skis.query;

import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.SqlType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Shared validation and construction rules for columns and other framework-owned expressions. */
final class SelectableSupport {

  private SelectableSupport() {}

  static <V> QueryCondition valueComparison(
      Selectable<V> selectable, ComparisonOperator operator, @Nullable V value) {
    Selectable<V> source = Objects.requireNonNull(selectable, "selectable");
    requireComparison(source, operator);
    return FrameworkQueryCondition.valueComparison(
        source, operator, requireValue(source, operator.name(), value));
  }

  static <V> QueryCondition parameterComparison(
      Selectable<V> selectable, ComparisonOperator operator, QueryParameter<V> parameter) {
    Selectable<V> source = Objects.requireNonNull(selectable, "selectable");
    requireComparison(source, operator);
    requireParameterType(source, parameter);
    return FrameworkQueryCondition.parameterComparison(source, operator, parameter);
  }

  static <V> QueryCondition expressionComparison(
      Selectable<V> left, ComparisonOperator operator, Selectable<V> right) {
    Selectable<V> checkedLeft = Objects.requireNonNull(left, "left");
    Selectable<V> checkedRight = Objects.requireNonNull(right, "right");
    requireCompatible(checkedLeft, operator, checkedRight);
    return FrameworkQueryCondition.expressionComparison(checkedLeft, operator, checkedRight);
  }

  static QueryCondition nullCheck(Selectable<?> selectable, NullOperator operator) {
    return FrameworkQueryCondition.nullCheck(
        Objects.requireNonNull(selectable, "selectable"),
        Objects.requireNonNull(operator, "operator"));
  }

  static <V> QueryCondition between(
      Selectable<V> selectable, @Nullable V lower, @Nullable V upper) {
    Selectable<V> source = Objects.requireNonNull(selectable, "selectable");
    requireOrdering(source, "between");
    return FrameworkQueryCondition.between(
        source,
        requireValue(source, "between lower bound", lower),
        requireValue(source, "between upper bound", upper));
  }

  static <V> QueryCondition betweenParameters(
      Selectable<V> selectable, QueryParameter<V> lower, QueryParameter<V> upper) {
    Selectable<V> source = Objects.requireNonNull(selectable, "selectable");
    requireOrdering(source, "between");
    requireParameterType(source, lower);
    requireParameterType(source, upper);
    return FrameworkQueryCondition.betweenParameters(source, lower, upper);
  }

  static QueryCondition like(Selectable<?> selectable, String pattern) {
    Selectable<?> source = Objects.requireNonNull(selectable, "selectable");
    requireLike(source);
    return likeString(source, Objects.requireNonNull(pattern, "pattern"));
  }

  static <V> QueryCondition likeParameter(Selectable<V> selectable, QueryParameter<V> pattern) {
    Selectable<V> source = Objects.requireNonNull(selectable, "selectable");
    requireLike(source);
    requireParameterType(source, pattern);
    return FrameworkQueryCondition.likeParameter(source, pattern);
  }

  @SuppressWarnings("unchecked")
  private static QueryCondition likeString(Selectable<?> selectable, String pattern) {
    return FrameworkQueryCondition.like((Selectable<String>) selectable, pattern);
  }

  static <V> QueryCondition membership(
      Selectable<V> selectable, Collection<? extends V> values, boolean negated) {
    Selectable<V> source = Objects.requireNonNull(selectable, "selectable");
    List<V> copy = copyValues(source, values, negated);
    return FrameworkQueryCondition.membership(source, copy, negated);
  }

  static <V> QueryCondition parameterMembership(
      Selectable<V> selectable,
      Collection<? extends QueryParameter<V>> parameters,
      boolean negated) {
    Selectable<V> source = Objects.requireNonNull(selectable, "selectable");
    Objects.requireNonNull(parameters, "parameters");
    if (source.sqlType() == SqlType.OTHER) {
      throw unsupported(source, negated ? "notInParameters" : "inParameters");
    }
    List<QueryParameter<V>> references = new ArrayList<>(parameters.size());
    for (QueryParameter<V> parameter : parameters) {
      requireParameterType(source, parameter);
      references.add(parameter);
    }
    return FrameworkQueryCondition.membershipParameters(source, references, negated);
  }

  static SortSpecification order(Selectable<?> selectable, SortDirection direction) {
    Selectable<?> source = Objects.requireNonNull(selectable, "selectable");
    requireOrdering(source, direction == SortDirection.ASC ? "asc" : "desc");
    return new SortSpecification(source, direction, NullPlacement.DIALECT_DEFAULT);
  }

  static String summary(Selectable<?> selectable) {
    Objects.requireNonNull(selectable, "selectable");
    if (selectable instanceof QueryColumn<?, ?> column) {
      String qualifier =
          column
              .table()
              .alias()
              .map(Identifier::value)
              .orElse(column.table().entity().table().name());
      return qualifier + '.' + column.property().name();
    }
    return selectable.expression().getClass().getSimpleName();
  }

  static boolean sameOccurrence(Selectable<?> left, Selectable<?> right) {
    if (left instanceof QueryColumn<?, ?> leftColumn
        && right instanceof QueryColumn<?, ?> rightColumn) {
      return leftColumn.table() == rightColumn.table()
          && leftColumn.property() == rightColumn.property();
    }
    return left == right;
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
      throw new QueryValidationException(
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
      throw new QueryValidationException(
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
      throw new QueryValidationException(
          operation
              + "(null) is not supported for expression '"
              + summary(selectable)
              + "'; use isNull()/isNotNull() for SQL null tests");
    }
    if (!selectable.javaType().isInstance(value)) {
      throw new QueryValidationException(
          "expression '"
              + summary(selectable)
              + "' requires "
              + selectable.javaType().getTypeName()
              + " but received "
              + value.getClass().getTypeName());
    }
    return selectable.javaType().cast(value);
  }

  private static <V> void requireParameterType(
      Selectable<V> selectable, QueryParameter<V> parameter) {
    Objects.requireNonNull(parameter, "parameter");
    if (!selectable.javaType().equals(parameter.javaType())) {
      throw new QueryValidationException(
          "expression '"
              + summary(selectable)
              + "' requires query parameter type "
              + selectable.javaType().getTypeName()
              + " but received "
              + parameter.javaType().getTypeName());
    }
  }

  private static QueryValidationException unsupported(Selectable<?> selectable, String operation) {
    return new QueryValidationException(
        operation
            + " is not supported for expression '"
            + summary(selectable)
            + "' with SQL type "
            + selectable.sqlType());
  }
}
