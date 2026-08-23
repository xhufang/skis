package io.skis.dialect;

import io.skis.metadata.TableMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Renderer for the portable single-table SELECT subset shared by initial dialects. */
public final class StandardSqlRenderer implements SqlRenderer {

  private final String dialectId;
  private final IdentifierRules identifierRules;
  private final DialectCapabilities capabilities;

  /** Creates a renderer with explicit dialect identity, identifier rules, and capabilities. */
  public StandardSqlRenderer(
      String dialectId,
      IdentifierRules identifierRules,
      DialectCapabilities capabilities) {
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
    if (statement instanceof SelectStatement select) {
      return renderSelect(select);
    }
    throw unsupported("statement", statement);
  }

  private RenderedSql renderSelect(SelectStatement statement) {
    RenderContext context = new RenderContext(statement.from());
    context.sql.append("SELECT ");
    for (int index = 0; index < statement.selections().size(); index++) {
      if (index > 0) {
        context.sql.append(", ");
      }
      renderExpression(statement.selections().get(index), context);
    }
    context.sql.append(" FROM ");
    renderTable(statement.from(), context.sql);
    statement
        .where()
        .ifPresent(
            predicate -> {
              context.sql.append(" WHERE ");
              renderPredicate(predicate, context);
            });
    return new RenderedSql(context.sql.toString(), context.parameters);
  }

  private void renderPredicate(SqlPredicate predicate, RenderContext context) {
    if (predicate instanceof ComparisonPredicate<?> comparison) {
      renderComparison(comparison, context);
      return;
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
      case ComparisonPredicate<?> comparison -> {
        context.sql.append('(');
        renderComparison(comparison, context);
        context.sql.append(')');
      }
      default -> throw unsupported("expression", expression);
    }
  }

  private void renderComparison(ComparisonPredicate<?> comparison, RenderContext context) {
    renderExpression(comparison.left(), context);
    context.sql.append(' ').append(renderOperator(comparison.operator())).append(' ');
    renderExpression(comparison.right(), context);
  }

  private String renderOperator(ComparisonOperator operator) {
    return switch (operator) {
      case EQUAL -> "=";
    };
  }

  private void renderColumn(ColumnExpression<?, ?> column, RenderContext context) {
    if (!column.table().equals(context.from)) {
      throw new SqlRenderException(
          "dialect '"
              + dialectId
              + "' cannot render a column outside the single FROM table");
    }
    String qualifier =
        column
            .table()
            .alias()
            .map(Identifier::value)
            .orElse(column.table().entity().table().name());
    context.sql.append(identifierRules.quote(qualifier));
    context.sql.append('.');
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
        .ifPresent(
            alias -> sql.append(" AS ").append(identifierRules.quote(alias.value())));
  }

  private void require(DialectFeature feature, String description) {
    if (!capabilities.supports(feature)) {
      throw new SqlRenderException(
          "dialect '" + dialectId + "' does not support " + description + " names");
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
    private final TableExpression<?> from;
    private final StringBuilder sql = new StringBuilder();
    private final List<ParameterSlot<?>> parameters = new ArrayList<>();

    private RenderContext(TableExpression<?> from) {
      this.from = from;
    }
  }
}
