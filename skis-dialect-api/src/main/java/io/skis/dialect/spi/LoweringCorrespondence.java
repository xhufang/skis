package io.skis.dialect.spi;

import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DecimalPrecision;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ReturningStatement;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateStatement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Verifies supported transformations against their original output, never a claimed type match. */
final class LoweringCorrespondence {

  private final List<ParameterRelationSource> parameterScopes = new ArrayList<>();
  private final DialectCapabilities capabilities;

  private LoweringCorrespondence(DialectCapabilities capabilities) {
    this.capabilities = capabilities;
  }

  static void validate(
      StatementAst original,
      StatementAst lowered,
      boolean aggregateStructure,
      DialectCapabilities capabilities) {
    if (original == lowered) {
      return;
    }
    require(original.getClass() == lowered.getClass(), "statement kind changed");
    LoweringCorrespondence check = new LoweringCorrespondence(capabilities);
    switch (original) {
      case ReturningStatement first -> {
        ReturningStatement second = (ReturningStatement) lowered;
        require(first.target() == second.target(), "mutation target changed");
        check.expressions(first.selections(), second.selections());
        validate(first.mutation(), second.mutation(), false, capabilities);
      }
      case InsertStatement first -> {
        InsertStatement second = (InsertStatement) lowered;
        require(
            first.target() == second.target() && first.columns().equals(second.columns()),
            "INSERT target or columns changed");
        check.expressions(first.values(), second.values());
      }
      case UpdateStatement first -> {
        UpdateStatement second = (UpdateStatement) lowered;
        require(
            first.target() == second.target()
                && first.assignments().size() == second.assignments().size(),
            "UPDATE shape changed");
        for (int index = 0; index < first.assignments().size(); index++) {
          var before = first.assignments().get(index);
          var after = second.assignments().get(index);
          require(before.column().equals(after.column()), "UPDATE target column changed");
          check.expression(before.value(), after.value());
        }
        check.expression(first.where(), second.where());
      }
      case DeleteStatement first -> {
        DeleteStatement second = (DeleteStatement) lowered;
        require(first.target() == second.target(), "DELETE target changed");
        check.expression(first.where(), second.where());
      }
      case SelectStatement first ->
          check.select(first, (SelectStatement) lowered, aggregateStructure);
      case CountAst first -> {
        CountAst second = (CountAst) lowered;
        check.sources(first.fromClause(), second.fromClause(), aggregateStructure);
        check.optionalExpression(first.predicate().orElse(null), second.predicate().orElse(null));
        check.optionalExpression(
            first.distinctExpression().orElse(null), second.distinctExpression().orElse(null));
        require(
            first.nullCompensated() == second.nullCompensated(), "count NULL compensation changed");
      }
      default -> {}
    }
  }

  private void select(SelectStatement original, SelectStatement lowered, boolean nested) {
    if (original == lowered) {
      return;
    }
    int scopeDepth = parameterScopes.size();
    try {
      require(original.distinct() == lowered.distinct(), "SELECT DISTINCT changed");
      require(
          original.selections().size() == lowered.selections().size(),
          "visible output count changed");
      require(
          original.hiddenSelections().size() == lowered.hiddenSelections().size(),
          "hidden output count changed");
      sources(original.fromClause(), lowered.fromClause(), nested);
      for (int index = 0; index < original.selections().size(); index++) {
        expression(original.selections().get(index), lowered.selections().get(index));
      }
      for (int index = 0; index < original.hiddenSelections().size(); index++) {
        HiddenSelection first = original.hiddenSelections().get(index);
        HiddenSelection second = lowered.hiddenSelections().get(index);
        require(first.alias().equals(second.alias()), "hidden output alias changed");
        expression(first.expression(), second.expression());
      }
      if (nested) {
        optionalExpression(original.where().orElse(null), lowered.where().orElse(null));
        groupings(original.groupBy(), lowered.groupBy());
        optionalExpression(original.having().orElse(null), lowered.having().orElse(null));
        ordering(original, lowered);
        require(
            Objects.equals(original.pagination(), lowered.pagination()),
            "nested pagination changed");
      }
    } finally {
      while (parameterScopes.size() > scopeDepth) {
        parameterScopes.removeLast();
      }
    }
  }

  private void ordering(SelectStatement original, SelectStatement lowered) {
    List<OrderByItem> firstItems = original.orderBy();
    List<OrderByItem> secondItems = lowered.orderBy();
    if (firstItems.equals(secondItems)) {
      return;
    }
    if (differentOrderingForms(firstItems, secondItems)) {
      // Share the exact portable rules, including NULL ranks and DISTINCT output references.
      // Only order representations are canonicalized; nested SELECT are not normalized again.
      firstItems = PortableLowering.normalizeOrdering(original, capabilities);
      secondItems = PortableLowering.normalizeOrdering(lowered, capabilities);
    }
    require(firstItems.size() == secondItems.size(), "nested ordering changed");
    for (int index = 0; index < firstItems.size(); index++) {
      OrderByItem first = firstItems.get(index);
      OrderByItem second = secondItems.get(index);
      require(
          first.direction() == second.direction() && first.nullOrder() == second.nullOrder(),
          "nested ordering changed");
      expression(first.expression(), second.expression());
    }
  }

  private static boolean differentOrderingForms(
      List<OrderByItem> original, List<OrderByItem> lowered) {
    if (original.size() != lowered.size()) {
      return true;
    }
    for (int index = 0; index < original.size(); index++) {
      OrderByItem first = original.get(index);
      OrderByItem second = lowered.get(index);
      if (first.nullOrder() != second.nullOrder()
          || (first.expression() instanceof SelectedOutputReference<?>)
              != (second.expression() instanceof SelectedOutputReference<?>)) {
        return true;
      }
    }
    return false;
  }

  private void sources(FromClause original, FromClause lowered, boolean strict) {
    int originalCount = original.joins().size();
    boolean addedParameters =
        lowered.joins().size() == originalCount + 1
            && lowered.joins().getLast().right() instanceof ParameterRelationSource;
    if (addedParameters) {
      JoinClause tail = lowered.joins().getLast();
      require(
          tail.type() == JoinType.CROSS && tail.on().isEmpty(),
          "parameter relation must be a trailing CROSS JOIN");
      ParameterRelationSource source = (ParameterRelationSource) tail.right();
      parameterScopes.add(source);
    }
    if (strict || addedParameters) {
      require(
          lowered.joins().size() == originalCount + (addedParameters ? 1 : 0),
          "original join chain changed");
      relation(original.root(), lowered.root());
      for (int index = 0; index < originalCount; index++) {
        JoinClause first = original.joins().get(index);
        JoinClause second = lowered.joins().get(index);
        require(first.type() == second.type(), "original join type changed");
        relation(first.right(), second.right());
        optionalExpression(first.on().orElse(null), second.on().orElse(null));
      }
    } else {
      // Preserve the existing root-lowering contract, while checking each changed derived output.
      int count = Math.min(original.occurrences().size(), lowered.occurrences().size());
      for (int index = 0; index < count; index++) {
        RelationSource first = original.occurrences().get(index).source();
        RelationSource second = lowered.occurrences().get(index).source();
        if (first instanceof DerivedRelationSource && second instanceof DerivedRelationSource) {
          relation(first, second);
        }
      }
    }
  }

  private void relation(RelationSource original, RelationSource lowered) {
    if (original
            instanceof
            DerivedRelationSource(SelectStatement statement, DerivedRelationReference reference)
        && lowered
            instanceof
            DerivedRelationSource(
                SelectStatement statement1,
                DerivedRelationReference reference1)) {
      require(reference.equals(reference1), "derived output shape changed");
      select(statement, statement1, true);
    } else {
      require(original.equals(lowered), "original relation changed");
    }
  }

  private void expression(SqlExpression<?> original, SqlExpression<?> lowered) {
    if (original == lowered) {
      return;
    }
    require(sameDescriptor(original, lowered), "output descriptor changed");
    if (original instanceof InPredicate<?> in
        && in.candidates().isEmpty()
        && !(lowered instanceof InPredicate<?>)) {
      expression(PortableLowering.emptyMembership(in), lowered);
      return;
    }
    if (original instanceof ParameterSlot<?> parameter
        && lowered
            instanceof
            DerivedColumnExpression<?>(DerivedRelationReference relation, int outputOrdinal)) {
      ParameterRelationSource source = parameterSource(relation);
      require(
          source != null && parameter.equals(source.parameters().get(outputOrdinal)),
          "group parameter identity changed");
      return;
    }
    if (lowered instanceof CastExpression<?> cast && cast.decimalPrecision() == null) {
      if ((original instanceof ParameterSlot<?>
              || original instanceof LiteralExpression<?>
              || original instanceof BooleanPredicate)
          && sameDescriptor(original, cast.operand())) {
        expression(original, cast.operand());
        return;
      }
      if (original instanceof AggregateExpression<?> aggregate
          && aggregate.javaType() == Double.class
          && (aggregate.kind() == AggregateExpression.Kind.SUM
              || aggregate.kind() == AggregateExpression.Kind.AVG)
          && aggregate.operand().orElseThrow().sqlType() == SqlType.DOUBLE) {
        expression(original, cast.operand());
        return;
      }
    }
    require(original.getClass() == lowered.getClass(), "output expression kind changed");
    if (original instanceof AggregateExpression<?> first
        && lowered instanceof AggregateExpression<?> second) {
      require(
          first.kind() == second.kind() && first.distinct() == second.distinct(),
          "aggregate kind or DISTINCT changed");
      SqlExpression<?> firstOperand = first.operand().orElse(null);
      SqlExpression<?> secondOperand = second.operand().orElse(null);
      if (firstOperand != null
          && secondOperand instanceof CastExpression<?> cast
          && numericInputCast(first, cast)) {
        expression(firstOperand, cast.operand());
      } else {
        optionalExpression(firstOperand, secondOperand);
      }
      return;
    }
    headers(original, lowered);
    List<? extends SqlExpression<?>> firstOperands = AstExpressions.operands(original);
    List<? extends SqlExpression<?>> secondOperands = AstExpressions.operands(lowered);
    expressions(firstOperands, secondOperands);
    SelectStatement firstSubquery = AstExpressions.subquery(original);
    SelectStatement secondSubquery = AstExpressions.subquery(lowered);
    if (firstSubquery != null && secondSubquery != null) {
      select(firstSubquery, secondSubquery, true);
    } else {
      require(firstSubquery == secondSubquery, "output subquery changed");
      if (firstOperands.isEmpty()) {
        require(original.equals(lowered), "output leaf or logical parameter changed");
      }
    }
  }

  private static boolean numericInputCast(AggregateExpression<?> original, CastExpression<?> cast) {
    SqlExpression<?> operand = original.operand().orElseThrow();
    if (original.kind() == AggregateExpression.Kind.SUM && operand.sqlType() == SqlType.REAL) {
      return cast.javaType() == Double.class
          && cast.sqlType() == SqlType.DOUBLE
          && cast.decimalPrecision() == null;
    }
    if (original.kind() == AggregateExpression.Kind.AVG) {
      int precision =
          switch (operand.sqlType()) {
            case TINYINT -> 3;
            case SMALLINT -> 5;
            case INTEGER -> 10;
            default -> 0;
          };
      return precision != 0
          && cast.javaType() == BigDecimal.class
          && cast.sqlType() == SqlType.DECIMAL
          && new DecimalPrecision(precision, 0).equals(cast.decimalPrecision());
    }
    return false;
  }

  private @Nullable ParameterRelationSource parameterSource(DerivedRelationReference reference) {
    for (int index = parameterScopes.size() - 1; index >= 0; index--) {
      ParameterRelationSource source = parameterScopes.get(index);
      if (source.reference() == reference) {
        return source;
      }
    }
    return null;
  }

  private static void headers(SqlExpression<?> original, SqlExpression<?> lowered) {
    boolean matches =
        switch (original) {
          case ArithmeticExpression<?> first ->
              first.operator() == ((ArithmeticExpression<?>) lowered).operator();
          case CastExpression<?> first ->
              Objects.equals(
                  first.decimalPrecision(), ((CastExpression<?>) lowered).decimalPrecision());
          case CaseExpression<?> first ->
              first.branches().size() == ((CaseExpression<?>) lowered).branches().size()
                  && first.otherwise().isPresent()
                      == ((CaseExpression<?>) lowered).otherwise().isPresent();
          case ComparisonPredicate<?> first ->
              first.operator() == ((ComparisonPredicate<?>) lowered).operator();
          case LogicalPredicate first ->
              first.operator() == ((LogicalPredicate) lowered).operator();
          case NullPredicate first -> first.operator() == ((NullPredicate) lowered).operator();
          case InPredicate<?> first -> first.negated() == ((InPredicate<?>) lowered).negated();
          case InSubqueryPredicate<?> first ->
              first.negated() == ((InSubqueryPredicate<?>) lowered).negated();
          case ExistsPredicate first -> first.negated() == ((ExistsPredicate) lowered).negated();
          default -> true;
        };
    require(matches, "output operation changed");
  }

  private void expressions(
      List<? extends SqlExpression<?>> original, List<? extends SqlExpression<?>> lowered) {
    require(original.size() == lowered.size(), "output operand count changed");
    for (int index = 0; index < original.size(); index++) {
      expression(original.get(index), lowered.get(index));
    }
  }

  private void groupings(List<SqlExpression<?>> original, List<SqlExpression<?>> lowered) {
    Map<SqlExpression<?>, Integer> typedValues = new HashMap<>();
    int index = 0;
    for (SqlExpression<?> key : original) {
      SqlExpression<?> value = typedGroupingValue(key);
      Integer previous = value == null ? null : typedValues.get(value);
      boolean nextMatchesValue =
          value != null
              && index < lowered.size()
              && value.equals(typedGroupingValue(lowered.get(index)));
      if (previous != null && !nextMatchesValue) {
        expression(key, lowered.get(previous));
      } else {
        require(index < lowered.size(), "nested grouping key removed");
        expression(key, lowered.get(index));
        if (value != null) {
          typedValues.put(value, index);
        }
        index++;
      }
    }
    require(index == lowered.size(), "nested grouping key added or reordered");
  }

  private static @Nullable SqlExpression<?> typedGroupingValue(SqlExpression<?> expression) {
    while (expression instanceof CastExpression<?> cast
        && cast.decimalPrecision() == null
        && sameDescriptor(cast, cast.operand())) {
      expression = cast.operand();
    }
    if (expression instanceof InPredicate<?> in && in.candidates().isEmpty()) {
      expression = PortableLowering.emptyMembership(in);
    }
    return expression instanceof ParameterSlot<?>
            || expression instanceof LiteralExpression<?>
            || expression instanceof BooleanPredicate
        ? expression
        : null;
  }

  private void optionalExpression(
      @Nullable SqlExpression<?> original, @Nullable SqlExpression<?> lowered) {
    if (original == null || lowered == null) {
      require(original == lowered, "optional expression changed");
    } else {
      expression(original, lowered);
    }
  }

  private static boolean sameDescriptor(SqlExpression<?> first, SqlExpression<?> second) {
    return first.javaType().equals(second.javaType())
        && first.sqlType() == second.sqlType()
        && first.nullability() == second.nullability();
  }

  private static void require(boolean valid, String detail) {
    if (!valid) {
      throw new IllegalArgumentException("lowering output correspondence failed: " + detail);
    }
  }
}
