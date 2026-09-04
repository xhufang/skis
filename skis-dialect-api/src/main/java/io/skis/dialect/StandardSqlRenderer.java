package io.skis.dialect;

import io.skis.metadata.TableMeta;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.TableOccurrence;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Renderer for the portable SELECT/Join and single-table mutation subset shared by dialects. */
public final class StandardSqlRenderer implements SqlRenderer {

  private final String dialectId;
  private final IdentifierRules identifierRules;
  private final DialectCapabilities capabilities;

  /** Creates a renderer with explicit dialect identity, identifier rules, and capabilities. */
  public StandardSqlRenderer(
      String dialectId, IdentifierRules identifierRules, DialectCapabilities capabilities) {
    Objects.requireNonNull(dialectId, "dialectId");
    if (dialectId.isBlank()) {
      throw new IllegalArgumentException("dialectId must not be blank");
    }
    this.dialectId = dialectId;
    this.identifierRules = Objects.requireNonNull(identifierRules, "identifierRules");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
  }

  @Override
  public RenderedSql render(StatementAst statement) {
    Objects.requireNonNull(statement, "statement");
    DialectJoinFeatures.validate(dialectId, capabilities, statement);
    return switch (statement) {
      case SelectStatement select -> renderSelect(select);
      case CountAst count -> renderCount(count);
      case InsertStatement insert -> renderInsert(insert);
      case UpdateStatement update -> renderUpdate(update);
      case DeleteStatement delete -> renderDelete(delete);
      default -> throw unsupported("statement", statement);
    };
  }

  private RenderedSql renderSelect(SelectStatement statement) {
    RenderContext context = new RenderContext(statement.fromClause());
    context.sql.append("SELECT ");
    if (statement.distinct()) {
      context.sql.append("DISTINCT ");
    }
    for (int index = 0; index < statement.selections().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderExpression(statement.selections().get(index), context);
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
              require(DialectFeature.PARAMETERIZED_LIMIT, "parameterized LIMIT");
              context.sql.append(" LIMIT ");
              renderExpression(pagination.limit(), context);
              if (pagination instanceof OffsetLimit offset) {
                require(DialectFeature.PARAMETERIZED_OFFSET, "parameterized OFFSET");
                context.sql.append(" OFFSET ");
                renderExpression(offset.offset(), context);
              }
            });
    return new RenderedSql(context.sql.toString(), context.parameters);
  }

  private RenderedSql renderCount(CountAst statement) {
    RenderContext context = new RenderContext(statement.fromClause());
    context.sql.append("SELECT ");
    if (statement.distinctExpression().isPresent()) {
      require(DialectFeature.COUNT_DISTINCT, "distinct count");
      SqlExpression<?> expression = statement.distinctExpression().orElseThrow();
      context.sql.append("COUNT(DISTINCT ");
      renderExpression(expression, context);
      context.sql.append(')');
      if (expression.nullable()) {
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
    return new RenderedSql(context.sql.toString(), context.parameters);
  }

  private void renderOrderByItem(OrderByItem item, RenderContext context) {
    NullOrder nullOrder = item.nullOrder();
    if (nullOrder != NullOrder.DIALECT_DEFAULT
        && !capabilities.supports(DialectFeature.NULLS_FIRST_LAST)) {
      context.sql.append("CASE WHEN ");
      renderExpression(item.expression(), context);
      context.sql.append(" IS NULL THEN ");
      context.sql.append(nullOrder == NullOrder.FIRST ? '0' : '1');
      context.sql.append(" ELSE ");
      context.sql.append(nullOrder == NullOrder.FIRST ? '1' : '0');
      context.sql.append(" END ASC, ");
    }
    renderExpression(item.expression(), context);
    context.sql.append(item.direction() == OrderDirection.ASC ? " ASC" : " DESC");
    if (nullOrder != NullOrder.DIALECT_DEFAULT
        && capabilities.supports(DialectFeature.NULLS_FIRST_LAST)) {
      context.sql.append(nullOrder == NullOrder.FIRST ? " NULLS FIRST" : " NULLS LAST");
    }
  }

  private RenderedSql renderInsert(InsertStatement statement) {
    requireUnaliasedMutationTarget(statement.target());
    RenderContext context = new RenderContext(statement.target());
    context.sql.append("INSERT INTO ");
    renderTable(statement.target(), context.sql);
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
    return new RenderedSql(context.sql.toString(), context.parameters);
  }

  private RenderedSql renderUpdate(UpdateStatement statement) {
    requireUnaliasedMutationTarget(statement.target());
    RenderContext context = new RenderContext(statement.target());
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
    return new RenderedSql(context.sql.toString(), context.parameters);
  }

  private RenderedSql renderDelete(DeleteStatement statement) {
    requireUnaliasedMutationTarget(statement.target());
    RenderContext context = new RenderContext(statement.target());
    context.sql.append("DELETE FROM ");
    renderTable(statement.target(), context.sql);
    context.sql.append(" WHERE ");
    renderPredicate(statement.where(), context);
    return new RenderedSql(context.sql.toString(), context.parameters);
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
      case NotPredicate not -> {
        renderNot(not, context);
        return;
      }
      default -> {}
    }
    throw unsupported("predicate", predicate);
  }

  private void renderExpression(SqlExpression<?> expression, RenderContext context) {
    switch (expression) {
      case ColumnExpression<?, ?> column -> renderColumn(column, context);
      case ParameterSlot<?> parameter -> {
        context.sql.append('?');
        context.parameters.add(parameter);
      }
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
    context.sql.append(" AS ").append(renderCastType(expression.sqlType())).append(')');
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
      context.sql.append(predicate.negated() ? "1 = 1" : "1 = 0");
      return;
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

  private String renderCastType(SqlType type) {
    return switch (type) {
      case BOOLEAN -> "BOOLEAN";
      case TINYINT, SMALLINT -> "SMALLINT";
      case INTEGER -> "INTEGER";
      case BIGINT -> "BIGINT";
      case REAL -> "REAL";
      case DOUBLE -> "DOUBLE PRECISION";
      case DECIMAL -> "DECIMAL";
      case CHARACTER -> "CHAR";
      case VARCHAR -> "VARCHAR";
      case UUID -> "UUID";
      case DATE -> "DATE";
      case TIME -> "TIME";
      case TIME_WITH_TIME_ZONE -> "TIME WITH TIME ZONE";
      case TIMESTAMP -> "TIMESTAMP";
      case TIMESTAMP_WITH_TIME_ZONE -> "TIMESTAMP WITH TIME ZONE";
      case VARBINARY, OTHER ->
          throw new SqlRenderException(
              "dialect '" + dialectId + "' cannot render CAST target " + type);
    };
  }

  private void renderColumn(ColumnExpression<?, ?> column, RenderContext context) {
    TableExpression<?> table = context.resolve(column.table());
    if (table == null) {
      throw new SqlRenderException(
          "dialect '" + dialectId + "' cannot render a column outside the FROM scope");
    }
    if (context.qualifyColumns) {
      String qualifier =
          table
              .alias()
              .map(Identifier::value)
              .orElse(table.entity().table().name());
      context.sql.append(identifierRules.quote(qualifier));
      context.sql.append('.');
    }
    context.sql.append(identifierRules.quote(column.property().column().name()));
  }

  private void renderColumnName(ColumnExpression<?, ?> column, RenderContext context) {
    if (context.resolve(column.table()) == null) {
      throw new SqlRenderException(
          "dialect '" + dialectId + "' cannot render a mutation column outside its target table");
    }
    context.sql.append(identifierRules.quote(column.property().column().name()));
  }

  private void renderTable(TableExpression<?> table, StringBuilder sql) {
    TableMeta metadata = table.entity().table();
    if (metadata.hasCatalog()) {
      require(DialectFeature.CATALOG_QUALIFIED_TABLES, "catalog-qualified table");
      sql.append(identifierRules.quote(metadata.catalog())).append('.');
    }
    if (metadata.hasSchema()) {
      require(DialectFeature.SCHEMA_QUALIFIED_TABLES, "schema-qualified table");
      sql.append(identifierRules.quote(metadata.schema())).append('.');
    }
    sql.append(identifierRules.quote(metadata.name()));
    table
        .alias()
        .ifPresent(alias -> sql.append(" AS ").append(identifierRules.quote(alias.value())));
  }

  private void renderFromClause(FromClause fromClause, RenderContext context) {
    renderTable(fromClause.root(), context.sql);
    for (JoinClause join : fromClause.joins()) {
      context.sql.append(' ').append(DialectJoinFeatures.keyword(join.type())).append(' ');
      renderTable(join.right(), context.sql);
      join.on()
          .ifPresent(
              predicate -> {
                context.sql.append(" ON ");
                renderPredicate(predicate, context);
              });
    }
  }

  private void require(DialectFeature feature, String description) {
    if (!capabilities.supports(feature)) {
      throw new SqlRenderException("dialect '" + dialectId + "' does not support " + description);
    }
  }

  private void requireUnaliasedMutationTarget(TableExpression<?> target) {
    if (target.alias().isPresent()) {
      throw new SqlRenderException(
          "dialect '" + dialectId + "' does not support aliases on mutation target tables");
    }
  }

  private SqlRenderException unsupported(String nodeKind, Object node) {
    return new SqlRenderException(
        "dialect '"
            + dialectId
            + "' cannot render "
            + nodeKind
            + " type "
            + node.getClass().getName());
  }

  private static final class RenderContext {
    private final @Nullable FromClause fromClause;
    private final @Nullable TableExpression<?> mutationTarget;
    private final boolean qualifyColumns;
    private final StringBuilder sql = new StringBuilder();
    private final List<ParameterSlot<?>> parameters = new ArrayList<>();

    private RenderContext(FromClause fromClause) {
      this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
      this.mutationTarget = null;
      this.qualifyColumns = true;
    }

    private RenderContext(TableExpression<?> mutationTarget) {
      this.fromClause = null;
      this.mutationTarget = Objects.requireNonNull(mutationTarget, "mutationTarget");
      this.qualifyColumns = false;
    }

    private @Nullable TableExpression<?> resolve(TableExpression<?> table) {
      if (fromClause != null) {
        return fromClause.occurrenceOf(table).map(TableOccurrence::table).orElse(null);
      }
      return mutationTarget == table ? mutationTarget : null;
    }
  }
}
