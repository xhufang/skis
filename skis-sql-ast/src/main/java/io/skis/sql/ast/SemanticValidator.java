package io.skis.sql.ast;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Central semantic validation for portable statement scope, types, nulls, and writability. */
public final class SemanticValidator {

  private SemanticValidator() {}

  /** Validates any statement node supported by the current portable AST. */
  public static void validate(StatementAst statement) {
    Objects.requireNonNull(statement, "statement");
    switch (statement) {
      case SelectStatement select -> validate(select);
      case InsertStatement insert ->
          validateInsert(insert.target(), insert.columns(), insert.values());
      case UpdateStatement update ->
          validateUpdate(update.target(), update.assignments(), update.where());
      case DeleteStatement delete -> validateDelete(delete.target(), delete.where());
      default ->
          throw new IllegalArgumentException(
              "unsupported statement node " + statement.getClass().getName());
    }
  }

  /** Validates the currently supported single-table SELECT scope and expression tree. */
  public static void validate(SelectStatement statement) {
    Objects.requireNonNull(statement, "statement");
    ValidationContext context = new ValidationContext(Set.of(statement.from()));
    statement.selections().forEach(context::validateExpression);
    statement.where().ifPresent(context::validateExpression);
    context.requireDenseParameterOrdinals();
  }

  static void validateInsert(
      TableExpression<?> target,
      List<ColumnExpression<?, ?>> columns,
      List<SqlExpression<?>> values) {
    requireWritableTarget(target, "INSERT");
    ValidationContext context = new ValidationContext(Set.of());
    for (int index = 0; index < columns.size(); index++) {
      ColumnExpression<?, ?> column = columns.get(index);
      SqlExpression<?> value = values.get(index);
      requireTargetColumn(column, target, "INSERT");
      if (!column.property().column().insertable()) {
        throw new IllegalArgumentException(
            "INSERT column '" + column.property().name() + "' is not insertable");
      }
      validateAssignment(column, value, "INSERT");
      context.validateExpression(value);
    }
    context.requireDenseParameterOrdinals();
  }

  static void validateUpdate(
      TableExpression<?> target,
      List<UpdateAssignment<?>> assignments,
      SqlPredicate where) {
    requireWritableTarget(target, "UPDATE");
    ValidationContext context = new ValidationContext(Set.of(target));
    for (UpdateAssignment<?> assignment : assignments) {
      ColumnExpression<?, ?> column = assignment.column();
      requireTargetColumn(column, target, "UPDATE");
      if (!column.property().column().updatable()) {
        throw new IllegalArgumentException(
            "UPDATE column '" + column.property().name() + "' is not updatable");
      }
      validateAssignment(column, assignment.value(), "UPDATE");
      context.validateExpression(assignment.value());
    }
    context.validateExpression(where);
    context.requireDenseParameterOrdinals();
  }

  static void validateDelete(TableExpression<?> target, SqlPredicate where) {
    requireWritableTarget(target, "DELETE");
    ValidationContext context = new ValidationContext(Set.of(target));
    context.validateExpression(where);
    context.requireDenseParameterOrdinals();
  }

  static void validateAssignment(
      ColumnExpression<?, ?> column, SqlExpression<?> value, String operation) {
    requireSameJavaType(column, value, operation + " value");
    if (!column.sqlType().equalityCompatibleWith(value.sqlType())) {
      throw new IllegalArgumentException(
          operation
              + " value SQL type "
              + value.sqlType()
              + " is not compatible with column '"
              + column.property().name()
              + "' of type "
              + column.sqlType());
    }
    if (!column.nullable() && value.nullable()) {
      throw new IllegalArgumentException(
          operation
              + " cannot assign a nullable expression to non-null column '"
              + column.property().name()
              + "'");
    }
  }

  static void validateComparison(
      SqlExpression<?> left, ComparisonOperator operator, SqlExpression<?> right) {
    requireSameJavaType(left, right, "comparison");
    requireNotAlwaysNull(left, "comparison left operand");
    requireNotAlwaysNull(right, "comparison right operand");
    boolean compatible =
        operator.isOrdered()
            ? left.sqlType().orderingCompatibleWith(right.sqlType())
            : left.sqlType().equalityCompatibleWith(right.sqlType());
    if (!compatible) {
      throw new IllegalArgumentException(
          "operator "
              + operator
              + " is not compatible with SQL types "
              + left.sqlType()
              + " and "
              + right.sqlType());
    }
  }

  static void validateBetween(
      SqlExpression<?> value, SqlExpression<?> lower, SqlExpression<?> upper) {
    requireSameJavaType(value, lower, "BETWEEN");
    requireSameJavaType(value, upper, "BETWEEN");
    requireNotAlwaysNull(value, "BETWEEN value");
    requireNotAlwaysNull(lower, "BETWEEN lower bound");
    requireNotAlwaysNull(upper, "BETWEEN upper bound");
    if (!value.sqlType().orderingCompatibleWith(lower.sqlType())
        || !value.sqlType().orderingCompatibleWith(upper.sqlType())) {
      throw new IllegalArgumentException(
          "BETWEEN requires compatible ordered SQL types but received "
              + value.sqlType()
              + ", "
              + lower.sqlType()
              + " and "
              + upper.sqlType());
    }
  }

  static void validateLike(SqlExpression<?> value, SqlExpression<?> pattern) {
    requireSameJavaType(value, pattern, "LIKE");
    requireNotAlwaysNull(value, "LIKE value");
    requireNotAlwaysNull(pattern, "LIKE pattern");
    if (!value.sqlType().supportsLike()
        || !value.sqlType().equalityCompatibleWith(pattern.sqlType())) {
      throw new IllegalArgumentException(
          "LIKE requires compatible character SQL types but received "
              + value.sqlType()
              + " and "
              + pattern.sqlType());
    }
  }

  static void validateIn(
      SqlExpression<?> value, List<? extends SqlExpression<?>> candidates) {
    if (candidates.isEmpty()) {
      return;
    }
    requireNotAlwaysNull(value, "IN value");
    for (SqlExpression<?> candidate : candidates) {
      requireSameJavaType(value, candidate, "IN");
      requireNotAlwaysNull(candidate, "IN candidate");
      if (!value.sqlType().equalityCompatibleWith(candidate.sqlType())) {
        throw new IllegalArgumentException(
            "IN requires compatible SQL types but received "
                + value.sqlType()
                + " and "
                + candidate.sqlType());
      }
    }
  }

  static void validateArithmetic(
      SqlExpression<?> left, ArithmeticOperator operator, SqlExpression<?> right) {
    Objects.requireNonNull(operator, "operator");
    requireSameJavaType(left, right, "arithmetic");
    if (!left.sqlType().isNumeric() || left.sqlType() != right.sqlType()) {
      throw new IllegalArgumentException(
          "arithmetic requires identical numeric SQL types but received "
              + left.sqlType()
              + " and "
              + right.sqlType());
    }
    if (operator == ArithmeticOperator.DIVIDE && left.javaType() == BigInteger.class) {
      throw new IllegalArgumentException(
          "BigInteger division is not portable because SQL DECIMAL division may produce "
              + "a fractional result; cast both operands to BigDecimal first");
    }
  }

  static void validateIncrement(SqlExpression<?> operand) {
    if (!operand.sqlType().isNumeric()) {
      throw new IllegalArgumentException(
          "increment requires a numeric SQL type but received " + operand.sqlType());
    }
  }

  static void validateConcat(List<? extends SqlExpression<String>> operands) {
    for (SqlExpression<String> operand : operands) {
      if (operand.javaType() != String.class || !operand.sqlType().supportsLike()) {
        throw new IllegalArgumentException(
            "concatenation requires character expressions but received "
                + operand.javaType().getTypeName()
                + "/"
                + operand.sqlType());
      }
    }
  }

  static <T> void validateCase(
      List<CaseWhen<T>> branches, Optional<SqlExpression<T>> otherwise) {
    SqlExpression<?> first = branches.getFirst().result();
    for (CaseWhen<T> branch : branches) {
      requireCompatibleResult(first, branch.result(), "CASE");
    }
    otherwise.ifPresent(result -> requireCompatibleResult(first, result, "CASE ELSE"));
  }

  static void validateCast(SqlExpression<?> operand, Class<?> javaType, SqlType sqlType) {
    requireJavaSqlDescriptor(javaType, sqlType, "CAST target");
    if (!operand.sqlType().castableTo(sqlType)) {
      throw new IllegalArgumentException(
          "CAST from " + operand.sqlType() + " to " + sqlType + " is not portable");
    }
  }

  static <T> void validateCoalesce(List<SqlExpression<T>> operands) {
    SqlExpression<?> first = operands.getFirst();
    for (SqlExpression<T> operand : operands) {
      requireCompatibleResult(first, operand, "COALESCE");
    }
  }

  static void validateLiteral(
      LiteralExpression.Kind kind,
      Class<?> javaType,
      SqlType sqlType,
      Nullability nullability) {
    requireJavaSqlDescriptor(javaType, sqlType, "literal");
    switch (kind) {
      case NULL -> {
        if (!nullability.isNullable() || javaType.isPrimitive() || sqlType == SqlType.OTHER) {
          throw new IllegalArgumentException(
              "NULL literal requires a nullable portable value type");
        }
      }
      case TRUE, FALSE -> {
        if (javaType != Boolean.class
            || sqlType != SqlType.BOOLEAN
            || nullability != Nullability.NON_NULL) {
          throw new IllegalArgumentException("boolean literal has an invalid type descriptor");
        }
      }
      case ZERO, ONE -> {
        if (!sqlType.isNumeric() || nullability != Nullability.NON_NULL) {
          throw new IllegalArgumentException("numeric literal requires a non-null numeric type");
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  static <T> Class<T> boxedJavaType(Class<T> javaType) {
    Objects.requireNonNull(javaType, "javaType");
    if (!javaType.isPrimitive()) {
      return javaType;
    }
    Class<?> boxed =
        switch (javaType.getName()) {
          case "boolean" -> Boolean.class;
          case "byte" -> Byte.class;
          case "short" -> Short.class;
          case "int" -> Integer.class;
          case "long" -> Long.class;
          case "float" -> Float.class;
          case "double" -> Double.class;
          case "char" -> Character.class;
          default -> throw new IllegalArgumentException("Java expression type must not be void");
        };
    return (Class<T>) boxed;
  }

  private static void requireWritableTarget(TableExpression<?> target, String operation) {
    Objects.requireNonNull(target, "target");
    if (target.entity().readOnly()) {
      throw new IllegalArgumentException(
          operation + " target entity '" + target.entity().entityName() + "' is read-only");
    }
  }

  private static void requireTargetColumn(
      ColumnExpression<?, ?> column, TableExpression<?> target, String operation) {
    if (!column.table().equals(target)) {
      throw new IllegalArgumentException(operation + " column does not belong to its target table");
    }
  }

  private static void requireCompatibleResult(
      SqlExpression<?> expected, SqlExpression<?> actual, String operation) {
    requireSameJavaType(expected, actual, operation);
    if (!expected.sqlType().equalityCompatibleWith(actual.sqlType())) {
      throw new IllegalArgumentException(
          operation
              + " result SQL types differ: "
              + expected.sqlType()
              + " and "
              + actual.sqlType());
    }
  }

  private static void requireSameJavaType(
      SqlExpression<?> left, SqlExpression<?> right, String operation) {
    if (!left.javaType().equals(right.javaType())) {
      throw new IllegalArgumentException(
          operation
              + " Java types differ: "
              + left.javaType().getTypeName()
              + " and "
              + right.javaType().getTypeName());
    }
  }

  private static void requireJavaSqlDescriptor(
      Class<?> javaType, SqlType sqlType, String description) {
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    if (javaType == void.class || javaType == Void.class) {
      throw new IllegalArgumentException(description + " Java type must not be void");
    }
    SqlType inferred = SqlType.fromJavaType(javaType);
    boolean compatible =
        inferred == SqlType.OTHER
            ? sqlType == SqlType.OTHER
            : inferred.equalityCompatibleWith(sqlType);
    if (!compatible) {
      throw new IllegalArgumentException(
          description
              + " Java type "
              + javaType.getTypeName()
              + " is not compatible with SQL type "
              + sqlType);
    }
  }

  private static void requireNotAlwaysNull(SqlExpression<?> expression, String description) {
    if (isAlwaysNull(expression)) {
      throw new IllegalArgumentException(
          description + " is always NULL; use IS NULL or IS NOT NULL for a null test");
    }
  }

  private static boolean isAlwaysNull(SqlExpression<?> expression) {
    return switch (expression) {
      case LiteralExpression<?> literal -> literal.isNullLiteral();
      case ArithmeticExpression<?> arithmetic ->
          isAlwaysNull(arithmetic.left()) || isAlwaysNull(arithmetic.right());
      case ConcatExpression concat ->
          concat.operands().stream().anyMatch(SemanticValidator::isAlwaysNull);
      case CaseExpression<?> caseExpression ->
          caseExpression.branches().stream()
                  .allMatch(branch -> isAlwaysNull(branch.result()))
              && caseExpression.otherwise().map(SemanticValidator::isAlwaysNull).orElse(true);
      case CastExpression<?> cast -> isAlwaysNull(cast.operand());
      case CoalesceExpression<?> coalesce ->
          coalesce.operands().stream().allMatch(SemanticValidator::isAlwaysNull);
      case IncrementExpression<?> increment -> isAlwaysNull(increment.operand());
      default -> false;
    };
  }

  private static final class ValidationContext {

    private final Set<TableExpression<?>> visibleTables;
    private final Map<Integer, ParameterSlot<?>> parametersByOrdinal = new HashMap<>();

    private ValidationContext(Set<TableExpression<?>> visibleTables) {
      this.visibleTables = Set.copyOf(visibleTables);
    }

    private void validateExpression(SqlExpression<?> expression) {
      Objects.requireNonNull(expression, "expression");
      requireJavaSqlDescriptor(expression.javaType(), expression.sqlType(), "expression");
      Objects.requireNonNull(expression.nullability(), "expression nullability");
      if (expression.nullable() && expression.javaType().isPrimitive()) {
        throw new IllegalArgumentException(
            "a nullable expression cannot use a primitive Java type");
      }

      switch (expression) {
        case ColumnExpression<?, ?> column -> validateColumn(column);
        case ParameterSlot<?> parameter -> validateParameter(parameter);
        case LiteralExpression<?> literal ->
            validateLiteral(
                literal.kind(), literal.javaType(), literal.sqlType(), literal.nullability());
        case ArithmeticExpression<?> arithmetic -> {
          validateArithmetic(arithmetic.left(), arithmetic.operator(), arithmetic.right());
          validateExpression(arithmetic.left());
          validateExpression(arithmetic.right());
        }
        case ConcatExpression concat -> {
          validateConcat(concat.operands());
          concat.operands().forEach(this::validateExpression);
        }
        case CaseExpression<?> caseExpression -> validateCaseExpression(caseExpression);
        case CastExpression<?> cast -> {
          validateCast(cast.operand(), cast.javaType(), cast.sqlType());
          validateExpression(cast.operand());
        }
        case CoalesceExpression<?> coalesce -> validateCoalesceExpression(coalesce);
        case ComparisonPredicate<?> comparison -> {
          validateComparison(comparison.left(), comparison.operator(), comparison.right());
          validateExpression(comparison.left());
          validateExpression(comparison.right());
        }
        case LogicalPredicate logical ->
            logical.operands().forEach(this::validateExpression);
        case NullPredicate nullPredicate -> validateExpression(nullPredicate.operand());
        case BetweenPredicate<?> between -> {
          validateBetween(between.value(), between.lower(), between.upper());
          validateExpression(between.value());
          validateExpression(between.lower());
          validateExpression(between.upper());
        }
        case LikePredicate like -> {
          validateLike(like.value(), like.pattern());
          validateExpression(like.value());
          validateExpression(like.pattern());
        }
        case InPredicate<?> in -> {
          validateIn(in.value(), in.candidates());
          validateExpression(in.value());
          in.candidates().forEach(this::validateExpression);
        }
        case NotPredicate not -> validateExpression(not.operand());
        case IncrementExpression<?> increment -> {
          validateIncrement(increment.operand());
          validateExpression(increment.operand());
        }
        default -> {
          // Custom opaque leaf expressions expose no portable child traversal contract yet.
        }
      }
    }

    private void validateColumn(ColumnExpression<?, ?> column) {
      if (!visibleTables.contains(column.table())) {
        throw new IllegalArgumentException(
            "column '"
                + column.property().name()
                + "' belongs to a different table expression or one that is not visible in this "
                + "statement scope");
      }
    }

    private void validateParameter(ParameterSlot<?> parameter) {
      ParameterSlot<?> existing = parametersByOrdinal.putIfAbsent(parameter.ordinal(), parameter);
      if (existing != null
          && (!existing.javaType().equals(parameter.javaType())
              || existing.sqlType() != parameter.sqlType()
              || existing.nullability() != parameter.nullability())) {
        throw new IllegalArgumentException(
            "parameter ordinal "
                + parameter.ordinal()
                + " has conflicting Java type, SQL type or nullability");
      }
    }

    private void validateCaseExpression(CaseExpression<?> caseExpression) {
      validateCaseUntyped(caseExpression);
      for (CaseWhen<?> branch : caseExpression.branches()) {
        validateExpression(branch.condition());
        validateExpression(branch.result());
      }
      caseExpression.otherwise().ifPresent(this::validateExpression);
    }

    private void validateCoalesceExpression(CoalesceExpression<?> coalesce) {
      validateCoalesceUntyped(coalesce);
      coalesce.operands().forEach(this::validateExpression);
    }

    private void requireDenseParameterOrdinals() {
      for (int expected = 0; expected < parametersByOrdinal.size(); expected++) {
        if (!parametersByOrdinal.containsKey(expected)) {
          throw new IllegalArgumentException(
              "parameter ordinals must be contiguous from zero; missing ordinal " + expected);
        }
      }
    }
  }

  private static <T> void validateCaseUntyped(CaseExpression<T> caseExpression) {
    validateCase(caseExpression.branches(), caseExpression.otherwise());
  }

  private static <T> void validateCoalesceUntyped(CoalesceExpression<T> coalesce) {
    validateCoalesce(coalesce.operands());
  }
}
