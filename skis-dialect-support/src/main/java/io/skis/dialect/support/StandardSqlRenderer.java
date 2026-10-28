package io.skis.dialect.support;

import io.skis.core.SkisErrorCode;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.RenderInput;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DecimalPrecision;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.EntityRelationSource;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ReturningStatement;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.TableOccurrence;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Renderer for the portable SELECT/Join and single-table mutation subset shared by dialects.
 *
 * <p>This type only serializes a target-bound {@link RenderInput}: capability validation, lowering,
 * normalization, post-validation, and final parameter layout have already completed. It never
 * reanalyzes a statement, infers nullability or types, rewrites structure, or decides parameter
 * positions.
 */
public final class StandardSqlRenderer implements SqlRenderer {

  private final Dialect dialect;
  private final String dialectId;
  private final IdentifierRules identifierRules;
  private final DialectCapabilities capabilities;
  private final TypeFormat typeFormat;

  private enum TypeFormat {
    STANDARD,
    POSTGRESQL,
    H2
  }

  /** Creates a renderer with standard type spellings, bound to one dialect composition root. */
  public StandardSqlRenderer(Dialect dialect) {
    this(dialect, TypeFormat.STANDARD);
  }

  /** Creates a renderer with PostgreSQL type spellings, independently of the dialect ID. */
  public static StandardSqlRenderer postgresql(Dialect dialect) {
    return new StandardSqlRenderer(dialect, TypeFormat.POSTGRESQL);
  }

  /** Creates a renderer with H2 decimal and temporal formats, independently of the dialect ID. */
  public static StandardSqlRenderer h2(Dialect dialect) {
    return new StandardSqlRenderer(dialect, TypeFormat.H2);
  }

  private StandardSqlRenderer(Dialect dialect, TypeFormat typeFormat) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.typeFormat = typeFormat;
    String id = Objects.requireNonNull(dialect.id(), "dialect.id");
    if (id.isBlank() || !id.equals(id.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("dialect id must be lowercase and non-blank");
    }
    this.dialectId = id;
    this.identifierRules =
        Objects.requireNonNull(dialect.identifierRules(), "dialect.identifierRules");
    this.capabilities = Objects.requireNonNull(dialect.capabilities(), "dialect.capabilities");
  }

  @Override
  public String render(RenderInput input) {
    Objects.requireNonNull(input, "input");
    input.requireTarget(dialect);
    RenderContext context = contextFor(input.statement());
    renderStatement(input.statement(), context);
    return context.sql.toString();
  }

  private RenderContext contextFor(StatementAst statement) {
    return switch (statement) {
      case ReturningStatement returning -> new RenderContext(returning.target());
      case SelectStatement select -> new RenderContext(select.fromClause());
      case CountAst count -> new RenderContext(count.fromClause());
      case InsertStatement insert -> new RenderContext(insert.target());
      case UpdateStatement update -> new RenderContext(update.target());
      case DeleteStatement delete -> new RenderContext(delete.target());
      default -> throw unsupported("statement", statement);
    };
  }

  private void renderStatement(StatementAst statement, RenderContext context) {
    switch (statement) {
      case ReturningStatement returning -> renderReturning(returning, context);
      case SelectStatement select -> renderSelect(select, context);
      case CountAst count -> renderCount(count, context);
      case InsertStatement insert -> renderInsert(insert, context);
      case UpdateStatement update -> renderUpdate(update, context);
      case DeleteStatement delete -> renderDelete(delete, context);
      default -> throw unsupported("statement", statement);
    }
  }

  private void renderReturning(ReturningStatement statement, RenderContext context) {
    boolean resultTable = statement.form() != ReturningStatement.Form.NATIVE;
    if (resultTable) {
      context.sql.append("SELECT ");
      renderReturningColumns(statement, context);
      context.sql.append(
          statement.form() == ReturningStatement.Form.OLD_TABLE
              ? " FROM OLD TABLE ("
              : " FROM FINAL TABLE (");
      renderStatement(statement.mutation(), context);
      context.sql.append(')');
    } else {
      renderStatement(statement.mutation(), context);
      context.sql.append(" RETURNING ");
      renderReturningColumns(statement, context);
    }
  }

  private void renderReturningColumns(ReturningStatement statement, RenderContext context) {
    for (int index = 0; index < statement.selections().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderColumnName(statement.selections().get(index), context);
    }
  }

  private void renderSelect(SelectStatement statement, RenderContext context) {
    renderSelect(statement, context, List.of());
  }

  private void renderSelect(
      SelectStatement statement, RenderContext context, List<Identifier> outputAliases) {
    if (!outputAliases.isEmpty() && outputAliases.size() != statement.selections().size()) {
      throw invalid("derived SELECT alias count does not match its visible output count");
    }
    context.sql.append("SELECT ");
    if (statement.distinct()) {
      context.sql.append("DISTINCT ");
    }
    for (int index = 0; index < statement.selections().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderExpression(statement.selections().get(index), context);
      if (!outputAliases.isEmpty()) {
        context.sql.append(" AS ").append(identifierRules.quote(outputAliases.get(index).value()));
      }
    }
    for (HiddenSelection selection : statement.hiddenSelections()) {
      context.sql.append(", ");
      renderExpression(selection.expression(), context);
      context.sql.append(" AS ").append(identifierRules.quote(selection.alias().value()));
    }
    context.sql.append(" FROM ");
    renderFromClause(statement.fromClause(), context);
    boolean hasWhere = statement.where().isPresent();
    boolean hasSeek = statement.pagination().filter(KeysetSeek.class::isInstance).isPresent();
    if (hasWhere || hasSeek) {
      context.sql.append(" WHERE ");
      if (hasWhere) {
        if (hasSeek) {
          context.sql.append('(');
        }
        renderPredicate(statement.where().orElseThrow(), context);
        if (hasSeek) {
          context.sql.append(')');
        }
      }
      if (hasWhere && hasSeek) {
        context.sql.append(" AND ");
      }
      if (hasSeek) {
        if (hasWhere) {
          context.sql.append('(');
        }
        renderPredicate(((KeysetSeek) statement.pagination().orElseThrow()).predicate(), context);
        if (hasWhere) {
          context.sql.append(')');
        }
      }
    }
    if (!statement.groupBy().isEmpty()) {
      context.sql.append(" GROUP BY ");
      for (int index = 0; index < statement.groupBy().size(); index++) {
        if (index > 0) {
          context.sql.append(", ");
        }
        renderExpression(statement.groupBy().get(index), context);
      }
    }
    statement
        .having()
        .ifPresent(
            having -> {
              context.sql.append(" HAVING ");
              renderPredicate(having, context);
            });
    if (!statement.orderBy().isEmpty()) {
      context.sql.append(" ORDER BY ");
      for (int index = 0; index < statement.orderBy().size(); index++) {
        if (index > 0) {
          context.sql.append(", ");
        }
        renderOrderByItem(statement.orderBy().get(index), context);
      }
    }
    statement
        .pagination()
        .ifPresent(
            pagination -> {
              context.sql.append(" LIMIT ");
              renderExpression(pagination.limit(), context);
              if (pagination instanceof OffsetLimit offset) {
                context.sql.append(" OFFSET ");
                renderExpression(offset.offset(), context);
              }
            });
  }

  private void renderCount(CountAst statement, RenderContext context) {
    context.sql.append("SELECT ");
    if (statement.distinctExpression().isPresent()) {
      SqlExpression<?> expression = statement.distinctExpression().orElseThrow();
      context.sql.append("COUNT(DISTINCT ");
      renderExpression(expression, context);
      context.sql.append(')');
      if (statement.nullCompensated()) {
        context.sql.append(" + CASE WHEN COUNT(*) > COUNT(");
        renderExpression(expression, context);
        context.sql.append(") THEN 1 ELSE 0 END");
      }
    } else {
      context.sql.append("COUNT(*)");
    }
    context.sql.append(" FROM ");
    renderFromClause(statement.fromClause(), context);
    statement
        .predicate()
        .ifPresent(
            predicate -> {
              context.sql.append(" WHERE ");
              renderPredicate(predicate, context);
            });
  }

  private void renderOrderByItem(OrderByItem item, RenderContext context) {
    NullOrder nullOrder = item.nullOrder();
    if (nullOrder != NullOrder.DIALECT_DEFAULT
        && !capabilities.supports(DialectFeature.NULLS_FIRST_LAST)) {
      throw SqlRenderException.of(
          SkisErrorCode.DIALECT_NULLS_ORDERING_UNSUPPORTED,
          dialectId,
          "NULLS_FIRST_LAST",
          "dialect '" + dialectId + "' received an unlowered NULLS FIRST/LAST ordering");
    }
    renderExpression(item.expression(), context);
    context.sql.append(item.direction() == OrderDirection.ASC ? " ASC" : " DESC");
    if (nullOrder != NullOrder.DIALECT_DEFAULT) {
      context.sql.append(nullOrder == NullOrder.FIRST ? " NULLS FIRST" : " NULLS LAST");
    }
  }

  private void renderInsert(InsertStatement statement, RenderContext context) {
    context.sql.append("INSERT INTO ");
    renderTable(statement.target(), context.sql);
    if (statement.columns().isEmpty()) {
      context.sql.append(" DEFAULT VALUES");
      return;
    }
    context.sql.append(" (");
    for (int index = 0; index < statement.columns().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderColumnName(statement.columns().get(index), context);
    }
    context.sql.append(") VALUES (");
    for (int index = 0; index < statement.values().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderExpression(statement.values().get(index), context);
    }
    context.sql.append(')');
  }

  private void renderUpdate(UpdateStatement statement, RenderContext context) {
    context.sql.append("UPDATE ");
    renderTable(statement.target(), context.sql);
    context.sql.append(" SET ");
    for (int index = 0; index < statement.assignments().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      UpdateAssignment<?> assignment = statement.assignments().get(index);
      renderColumnName(assignment.column(), context);
      context.sql.append(" = ");
      if (assignment.value() instanceof IncrementExpression<?> increment) {
        renderIncrement(increment, context, false);
      } else {
        renderExpression(assignment.value(), context);
      }
    }
    context.sql.append(" WHERE ");
    renderPredicate(statement.where(), context);
  }

  private void renderDelete(DeleteStatement statement, RenderContext context) {
    context.sql.append("DELETE FROM ");
    renderTable(statement.target(), context.sql);
    context.sql.append(" WHERE ");
    renderPredicate(statement.where(), context);
  }

  private void renderPredicate(SqlPredicate predicate, RenderContext context) {
    switch (predicate) {
      case ComparisonPredicate<?> comparison -> {
        renderComparison(comparison, context);
        return;
      }
      case LogicalPredicate logical -> {
        renderLogical(logical, context);
        return;
      }
      case NullPredicate nullPredicate -> {
        renderNull(nullPredicate, context);
        return;
      }
      case BetweenPredicate<?> between -> {
        renderBetween(between, context);
        return;
      }
      case LikePredicate like -> {
        renderLike(like, context);
        return;
      }
      case InPredicate<?> in -> {
        renderIn(in, context);
        return;
      }
      case InSubqueryPredicate<?> in -> {
        renderInSubquery(in, context);
        return;
      }
      case ExistsPredicate exists -> {
        renderExists(exists, context);
        return;
      }
      case NotPredicate not -> {
        renderNot(not, context);
        return;
      }
      case BooleanPredicate booleanPredicate -> {
        context.sql.append(booleanPredicate.value() ? "TRUE" : "FALSE");
        return;
      }
      default -> {}
    }
    throw unsupported("predicate", predicate);
  }

  private void renderExpression(SqlExpression<?> expression, RenderContext context) {
    switch (expression) {
      case AggregateExpression<?> aggregate -> {
        context.sql.append(aggregate.kind().name()).append('(');
        if (aggregate.distinct()) {
          context.sql.append("DISTINCT ");
        }
        if (aggregate.operand().isEmpty()) {
          context.sql.append('*');
        } else {
          renderExpression(aggregate.operand().orElseThrow(), context);
        }
        context.sql.append(')');
      }
      case ColumnExpression<?, ?> column -> renderColumn(column, context);
      case DerivedColumnExpression<?> column -> renderDerivedColumn(column, context);
      case ParameterSlot<?> ignored -> context.sql.append('?');
      case SelectedOutputReference<?> reference -> context.sql.append(reference.position());
      case BooleanPredicate predicate -> context.sql.append(predicate.value() ? "TRUE" : "FALSE");
      case LiteralExpression<?> literal -> renderLiteral(literal, context);
      case ArithmeticExpression<?> arithmetic -> renderArithmetic(arithmetic, context);
      case ConcatExpression concat -> renderConcat(concat, context);
      case CaseExpression<?> caseExpression -> renderCase(caseExpression, context);
      case CastExpression<?> cast -> renderCast(cast, context);
      case CoalesceExpression<?> coalesce -> renderCoalesce(coalesce, context);
      case ComparisonPredicate<?> comparison -> {
        context.sql.append('(');
        renderComparison(comparison, context);
        context.sql.append(')');
      }
      case IncrementExpression<?> increment -> renderIncrement(increment, context, true);
      case LogicalPredicate logical -> {
        context.sql.append('(');
        renderLogical(logical, context);
        context.sql.append(')');
      }
      case NullPredicate nullPredicate -> {
        context.sql.append('(');
        renderNull(nullPredicate, context);
        context.sql.append(')');
      }
      case BetweenPredicate<?> between -> {
        context.sql.append('(');
        renderBetween(between, context);
        context.sql.append(')');
      }
      case LikePredicate like -> {
        context.sql.append('(');
        renderLike(like, context);
        context.sql.append(')');
      }
      case InPredicate<?> in -> {
        context.sql.append('(');
        renderIn(in, context);
        context.sql.append(')');
      }
      case InSubqueryPredicate<?> in -> {
        context.sql.append('(');
        renderInSubquery(in, context);
        context.sql.append(')');
      }
      case ExistsPredicate exists -> {
        context.sql.append('(');
        renderExists(exists, context);
        context.sql.append(')');
      }
      case ScalarSubqueryExpression<?> scalar -> renderScalarSubquery(scalar, context);
      case NotPredicate not -> {
        context.sql.append('(');
        renderNot(not, context);
        context.sql.append(')');
      }
      default -> throw unsupported("expression", expression);
    }
  }

  private void renderLogical(LogicalPredicate logical, RenderContext context) {
    String operator = renderLogicalOperator(logical.operator());
    for (int index = 0; index < logical.operands().size(); index++) {
      if (index > 0) {
        context.sql.append(' ').append(operator).append(' ');
      }
      SqlPredicate operand = logical.operands().get(index);
      if (operand instanceof LogicalPredicate) {
        context.sql.append('(');
        renderPredicate(operand, context);
        context.sql.append(')');
      } else {
        renderPredicate(operand, context);
      }
    }
  }

  private void renderComparison(ComparisonPredicate<?> comparison, RenderContext context) {
    renderExpression(comparison.left(), context);
    context.sql.append(' ').append(renderOperator(comparison.operator())).append(' ');
    renderExpression(comparison.right(), context);
  }

  private void renderLiteral(LiteralExpression<?> literal, RenderContext context) {
    context.sql.append(
        switch (literal.kind()) {
          case NULL -> "NULL";
          case TRUE -> "TRUE";
          case FALSE -> "FALSE";
          case ZERO -> "0";
          case ONE -> "1";
        });
  }

  private void renderArithmetic(ArithmeticExpression<?> expression, RenderContext context) {
    context.sql.append('(');
    renderExpression(expression.left(), context);
    context.sql.append(' ').append(renderArithmeticOperator(expression.operator())).append(' ');
    renderExpression(expression.right(), context);
    context.sql.append(')');
  }

  private void renderConcat(ConcatExpression expression, RenderContext context) {
    context.sql.append('(');
    for (int index = 0; index < expression.operands().size(); index++) {
      if (index > 0) {
        context.sql.append(" || ");
      }
      renderExpression(expression.operands().get(index), context);
    }
    context.sql.append(')');
  }

  private void renderCase(CaseExpression<?> expression, RenderContext context) {
    context.sql.append("CASE");
    for (CaseWhen<?> branch : expression.branches()) {
      context.sql.append(" WHEN ");
      renderPredicate(branch.condition(), context);
      context.sql.append(" THEN ");
      renderExpression(branch.result(), context);
    }
    expression
        .otherwise()
        .ifPresent(
            otherwise -> {
              context.sql.append(" ELSE ");
              renderExpression(otherwise, context);
            });
    context.sql.append(" END");
  }

  private void renderCast(CastExpression<?> expression, RenderContext context) {
    context.sql.append("CAST(");
    renderExpression(expression.operand(), context);
    context
        .sql
        .append(" AS ")
        .append(
            renderCastType(
                expression.sqlType(), expression.javaType(), expression.decimalPrecision()))
        .append(')');
  }

  private void renderCoalesce(CoalesceExpression<?> expression, RenderContext context) {
    context.sql.append("COALESCE(");
    for (int index = 0; index < expression.operands().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderExpression(expression.operands().get(index), context);
    }
    context.sql.append(')');
  }

  private void renderIncrement(
      IncrementExpression<?> expression, RenderContext context, boolean parenthesize) {
    if (parenthesize) {
      context.sql.append('(');
    }
    renderExpression(expression.operand(), context);
    context.sql.append(" + 1");
    if (parenthesize) {
      context.sql.append(')');
    }
  }

  private void renderNull(NullPredicate predicate, RenderContext context) {
    renderExpression(predicate.operand(), context);
    context.sql.append(predicate.operator() == NullOperator.IS_NULL ? " IS NULL" : " IS NOT NULL");
  }

  private void renderBetween(BetweenPredicate<?> predicate, RenderContext context) {
    renderExpression(predicate.value(), context);
    context.sql.append(" BETWEEN ");
    renderExpression(predicate.lower(), context);
    context.sql.append(" AND ");
    renderExpression(predicate.upper(), context);
  }

  private void renderLike(LikePredicate predicate, RenderContext context) {
    renderExpression(predicate.value(), context);
    context.sql.append(" LIKE ");
    renderExpression(predicate.pattern(), context);
  }

  private void renderIn(InPredicate<?> predicate, RenderContext context) {
    if (predicate.candidates().isEmpty()) {
      throw invalid("empty IN/NOT IN reached the renderer without portable normalization");
    }
    renderExpression(predicate.value(), context);
    context.sql.append(predicate.negated() ? " NOT IN (" : " IN (");
    for (int index = 0; index < predicate.candidates().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderExpression(predicate.candidates().get(index), context);
    }
    context.sql.append(')');
  }

  private void renderInSubquery(InSubqueryPredicate<?> predicate, RenderContext context) {
    renderExpression(predicate.value(), context);
    context.sql.append(predicate.negated() ? " NOT IN (" : " IN (");
    renderSelect(predicate.subquery(), context.child(predicate.subquery().fromClause()));
    context.sql.append(')');
  }

  private void renderExists(ExistsPredicate predicate, RenderContext context) {
    context.sql.append(predicate.negated() ? "NOT EXISTS (" : "EXISTS (");
    renderSelect(predicate.subquery(), context.child(predicate.subquery().fromClause()));
    context.sql.append(')');
  }

  private void renderScalarSubquery(ScalarSubqueryExpression<?> expression, RenderContext context) {
    context.sql.append('(');
    renderSelect(expression.subquery(), context.child(expression.subquery().fromClause()));
    context.sql.append(')');
  }

  private void renderNot(NotPredicate predicate, RenderContext context) {
    context.sql.append("NOT (");
    renderPredicate(predicate.operand(), context);
    context.sql.append(')');
  }

  private String renderOperator(ComparisonOperator operator) {
    return switch (operator) {
      case EQUAL -> "=";
      case NOT_EQUAL -> "<>";
      case GREATER_THAN -> ">";
      case GREATER_THAN_OR_EQUAL -> ">=";
      case LESS_THAN -> "<";
      case LESS_THAN_OR_EQUAL -> "<=";
    };
  }

  private String renderJoinKeyword(JoinType type) {
    return switch (type) {
      case INNER -> "INNER JOIN";
      case LEFT -> "LEFT JOIN";
      case RIGHT -> "RIGHT JOIN";
      case FULL -> "FULL JOIN";
      case CROSS -> "CROSS JOIN";
    };
  }

  private String renderLogicalOperator(LogicalOperator operator) {
    return switch (operator) {
      case AND -> "AND";
      case OR -> "OR";
    };
  }

  private String renderArithmeticOperator(ArithmeticOperator operator) {
    return switch (operator) {
      case ADD -> "+";
      case SUBTRACT -> "-";
      case MULTIPLY -> "*";
      case DIVIDE -> "/";
    };
  }

  private String renderCastType(
      SqlType type, Class<?> javaType, @Nullable DecimalPrecision precision) {
    if (precision != null) {
      return "DECIMAL(" + precision.precision() + "," + precision.scale() + ")";
    }
    return switch (type) {
      case BOOLEAN -> "BOOLEAN";
      case TINYINT, SMALLINT -> "SMALLINT";
      case INTEGER -> "INTEGER";
      case BIGINT -> "BIGINT";
      case REAL -> "REAL";
      case DOUBLE -> "DOUBLE PRECISION";
      // H2's unconstrained NUMERIC defaults to scale zero. DECFLOAT retains variable scale
      // within the engine's native precision instead of silently rounding arbitrary decimals.
      case DECIMAL ->
          typeFormat == TypeFormat.H2 && javaType == java.math.BigDecimal.class
              ? "DECFLOAT"
              : "DECIMAL";
      case CHARACTER -> "CHAR";
      case VARCHAR -> "VARCHAR";
      case UUID -> "UUID";
      case DATE -> "DATE";
      case TIME -> typeFormat == TypeFormat.H2 ? "TIME(9)" : "TIME";
      case TIME_WITH_TIME_ZONE ->
          typeFormat == TypeFormat.H2 ? "TIME(9) WITH TIME ZONE" : "TIME WITH TIME ZONE";
      case TIMESTAMP -> typeFormat == TypeFormat.H2 ? "TIMESTAMP(9)" : "TIMESTAMP";
      case TIMESTAMP_WITH_TIME_ZONE ->
          typeFormat == TypeFormat.H2 ? "TIMESTAMP(9) WITH TIME ZONE" : "TIMESTAMP WITH TIME ZONE";
      case VARBINARY -> typeFormat == TypeFormat.POSTGRESQL ? "BYTEA" : "VARBINARY";
      case OTHER -> throw invalid("cannot render CAST target " + type);
    };
  }

  private void renderColumn(ColumnExpression<?, ?> column, RenderContext context) {
    TableExpression<?> table = context.resolve(column.table());
    if (table == null) {
      throw invalid("cannot render a column outside the FROM scope");
    }
    if (context.qualifyColumns) {
      String qualifier = table.alias().map(Identifier::value).orElse(table.entity().table().name());
      context.sql.append(identifierRules.quote(qualifier));
      context.sql.append('.');
    }
    context.sql.append(identifierRules.quote(column.property().column().name()));
  }

  private void renderDerivedColumn(DerivedColumnExpression<?> column, RenderContext context) {
    DerivedRelationReference relation = context.resolve(column.relation());
    if (relation == null) {
      throw invalid("cannot render a derived column outside the FROM scope");
    }
    context.sql.append(identifierRules.quote(relation.alias().value()));
    context.sql.append('.');
    context.sql.append(identifierRules.quote(column.output().name().value()));
  }

  private void renderColumnName(ColumnExpression<?, ?> column, RenderContext context) {
    if (context.resolve(column.table()) == null) {
      throw invalid("cannot render a mutation column outside its target table");
    }
    context.sql.append(identifierRules.quote(column.property().column().name()));
  }

  private void renderTable(TableExpression<?> table, StringBuilder sql) {
    TableMeta metadata = table.entity().table();
    if (metadata.hasCatalog()) {
      sql.append(identifierRules.quote(metadata.catalog())).append('.');
    }
    if (metadata.hasSchema()) {
      sql.append(identifierRules.quote(metadata.schema())).append('.');
    }
    sql.append(identifierRules.quote(metadata.name()));
    table
        .alias()
        .ifPresent(alias -> sql.append(" AS ").append(identifierRules.quote(alias.value())));
  }

  private void renderFromClause(FromClause fromClause, RenderContext context) {
    renderRelation(fromClause.root(), context);
    for (JoinClause join : fromClause.joins()) {
      context.sql.append(' ').append(renderJoinKeyword(join.type())).append(' ');
      renderRelation(join.right(), context);
      join.on()
          .ifPresent(
              predicate -> {
                context.sql.append(" ON ");
                renderPredicate(predicate, context);
              });
    }
  }

  private void renderRelation(RelationSource source, RenderContext context) {
    switch (source) {
      case EntityRelationSource entity -> renderTable(entity.table(), context.sql);
      case DerivedRelationSource derived -> {
        context.sql.append('(');
        List<Identifier> aliases =
            derived.reference().outputs().stream().map(DerivedOutputColumn::name).toList();
        renderSelect(
            derived.statement(), context.relationChild(derived.statement().fromClause()), aliases);
        context
            .sql
            .append(") AS ")
            .append(identifierRules.quote(derived.reference().alias().value()));
      }
      case ParameterRelationSource parameters -> {
        context.sql.append("(VALUES (");
        for (int index = 0; index < parameters.parameters().size(); index++) {
          if (index > 0) {
            context.sql.append(", ");
          }
          var parameter = parameters.parameters().get(index);
          context
              .sql
              .append("CAST(? AS ")
              .append(renderCastType(parameter.sqlType(), parameter.javaType(), null))
              .append(')');
        }
        context
            .sql
            .append(")) AS ")
            .append(identifierRules.quote(parameters.reference().alias().value()))
            .append('(');
        for (int index = 0; index < parameters.reference().outputs().size(); index++) {
          if (index > 0) {
            context.sql.append(", ");
          }
          context.sql.append(
              identifierRules.quote(parameters.reference().output(index).name().value()));
        }
        context.sql.append(')');
      }
    }
  }

  private SqlRenderException invalid(String detail) {
    return SqlRenderException.of(SkisErrorCode.DIALECT_INVALID_STRUCTURE, dialectId, detail);
  }

  private SqlRenderException unsupported(String nodeKind, Object node) {
    return SqlRenderException.of(
        SkisErrorCode.DIALECT_RENDER_FAILURE,
        dialectId,
        "cannot render " + nodeKind + " type " + node.getClass().getName());
  }

  private static final class RenderContext {
    private final @Nullable FromClause fromClause;
    private final @Nullable TableExpression<?> mutationTarget;
    private final @Nullable RenderContext parent;
    private final boolean qualifyColumns;
    private final StringBuilder sql;

    private RenderContext(FromClause fromClause) {
      this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
      this.mutationTarget = null;
      this.parent = null;
      this.qualifyColumns = true;
      this.sql = new StringBuilder();
    }

    private RenderContext(FromClause fromClause, RenderContext parent) {
      this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
      this.mutationTarget = null;
      this.parent = Objects.requireNonNull(parent, "parent");
      this.qualifyColumns = true;
      this.sql = parent.sql;
    }

    private RenderContext(FromClause fromClause, StringBuilder sql) {
      this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
      this.mutationTarget = null;
      this.parent = null;
      this.qualifyColumns = true;
      this.sql = Objects.requireNonNull(sql, "sql");
    }

    private RenderContext(TableExpression<?> mutationTarget) {
      this.fromClause = null;
      this.mutationTarget = Objects.requireNonNull(mutationTarget, "mutationTarget");
      this.parent = null;
      this.qualifyColumns = false;
      this.sql = new StringBuilder();
    }

    private RenderContext child(FromClause childFromClause) {
      return new RenderContext(childFromClause, this);
    }

    private RenderContext relationChild(FromClause childFromClause) {
      return new RenderContext(childFromClause, sql);
    }

    private @Nullable TableExpression<?> resolve(TableExpression<?> table) {
      if (fromClause != null) {
        TableExpression<?> local =
            fromClause.occurrenceOf(table).flatMap(TableOccurrence::entityTable).orElse(null);
        if (local != null) {
          return local;
        }
      }
      if (mutationTarget == table) {
        return mutationTarget;
      }
      return parent == null ? null : parent.resolve(table);
    }

    private @Nullable DerivedRelationReference resolve(DerivedRelationReference relation) {
      if (fromClause != null) {
        DerivedRelationReference local =
            fromClause
                .occurrenceOf(relation)
                .flatMap(TableOccurrence::derivedReference)
                .orElse(null);
        if (local != null) {
          return local;
        }
      }
      return parent == null ? null : parent.resolve(relation);
    }
  }
}
