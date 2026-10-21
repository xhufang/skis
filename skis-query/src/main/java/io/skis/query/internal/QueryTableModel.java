package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.EntityRelationSource;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.TableExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Canonical state and AST model behind the public generated-table base. */
public final class QueryTableModel<E> {

  private final QueryTable<E> owner;

  private final TableNode<E> tableNode;
  private final List<QueryColumn<E, ?>> columns;
  private final List<ColumnExpression<E, ?>> selections;

  /** Creates an unaliased generated query table. */
  public QueryTableModel(QueryTable<E> owner, EntityMeta<E> entity) {
    this.owner = Objects.requireNonNull(owner, "owner");
    EntityMeta<E> checked = Objects.requireNonNull(entity, "entity");
    this.tableNode = new TableNode<>(this, checked);
    ColumnSet<E> columnSet = createColumns(checked);
    this.columns = columnSet.columns();
    this.selections = columnSet.selections();
  }

  /** Creates an independently aliased generated query table. */
  public QueryTableModel(QueryTable<E> owner, EntityMeta<E> entity, String alias) {
    this.owner = Objects.requireNonNull(owner, "owner");
    EntityMeta<E> checked = Objects.requireNonNull(entity, "entity");
    this.tableNode =
        new TableNode<>(
            this,
            checked,
            QueryDsl.identifier(
                alias,
                SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE,
                "query table alias is not a portable SQL identifier"));
    ColumnSet<E> columnSet = createColumns(checked);
    this.columns = columnSet.columns();
    this.selections = columnSet.selections();
  }

  /** Returns the canonical generated entity metadata. */
  public EntityMeta<E> entity() {
    return tableNode.entity();
  }

  /** Returns the validated alias text, if this is an aliased occurrence. */
  public Optional<String> alias() {
    return tableNode.alias().map(Identifier::value);
  }

  /** Returns the canonical generated column while preserving its declared nullability. */
  public <V> Selectable<V> queryColumn(PropertyMeta<E, V> property) {
    return internalColumn(property);
  }

  <V> QueryColumn<E, V> internalColumn(PropertyMeta<E, V> property) {
    Objects.requireNonNull(property, "property");
    int ordinal = property.ordinal();
    if (ordinal < 0 || ordinal >= columns.size() || columns.get(ordinal).property() != property) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_EXPRESSION_IDENTITY,
          "property '"
              + property.name()
              + "' does not belong to query table '"
              + entity().entityName()
              + "'");
    }
    @SuppressWarnings("unchecked")
    QueryColumn<E, V> column = (QueryColumn<E, V>) columns.get(ordinal);
    return column;
  }

  /** Returns the canonical generated column whose metadata declares a non-null SQL value. */
  @SuppressWarnings("unchecked")
  public <V> NonNullSelectable<V> nonNullQueryColumn(PropertyMeta<E, V> property) {
    QueryColumn<E, V> column = internalColumn(property);
    if (column.nullable()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_NULLABILITY_CONTRACT, "non-null query column uses nullable metadata");
    }
    return (NonNullSelectable<V>) column;
  }

  /** Returns the canonical generated column whose metadata permits SQL NULL. */
  public <V> Selectable<V> nullableQueryColumn(PropertyMeta<E, V> property) {
    QueryColumn<E, V> column = internalColumn(property);
    if (!column.nullable()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_NULLABILITY_CONTRACT, "nullable query column uses non-null metadata");
    }
    return column;
  }

  TableExpression<E> tableExpression() {
    return tableNode;
  }

  QueryRelation relation() {
    return tableNode;
  }

  static @Nullable QueryTable<?> ownerOf(TableExpression<?> expression) {
    return expression instanceof TableNode<?> node ? node.owner : null;
  }

  List<ColumnExpression<E, ?>> selections() {
    return selections;
  }

  /** Immutable DSL columns in canonical metadata order. */
  public List<? extends Selectable<?>> columns() {
    return columns;
  }

  @SuppressWarnings("unchecked")
  static <E> QueryTableModel<E> of(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    if (table.columns().isEmpty()
        || !(table.columns().getFirst() instanceof QueryColumn<?, ?> column)
        || !(column.expression().table() instanceof TableNode<?> node)
        || node.owner != table) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_EXPRESSION_IDENTITY, "table has no canonical generated columns");
    }
    return (QueryTableModel<E>) node.model;
  }

  @Override
  public boolean equals(@org.jspecify.annotations.Nullable Object other) {
    return this == other
        || other instanceof QueryTableModel<?> table && tableNode.equals(table.tableNode);
  }

  @Override
  public int hashCode() {
    return tableNode.hashCode();
  }

  private ColumnSet<E> createColumns(EntityMeta<E> entity) {
    List<QueryColumn<E, ?>> createdColumns = new ArrayList<>(entity.properties().size());
    List<ColumnExpression<E, ?>> createdSelections = new ArrayList<>(entity.properties().size());
    for (PropertyMeta<E, ?> property : entity.properties()) {
      addColumn(property, createdColumns, createdSelections);
    }
    return new ColumnSet<>(List.copyOf(createdColumns), List.copyOf(createdSelections));
  }

  private <V> void addColumn(
      PropertyMeta<E, V> property,
      List<QueryColumn<E, ?>> createdColumns,
      List<ColumnExpression<E, ?>> createdSelections) {
    ColumnExpression<E, V> expression = tableNode.columnFor(property);
    createdSelections.add(expression);
    createdColumns.add(
        expression.nullable()
            ? new NullableQueryColumn<>(owner, expression)
            : new NonNullQueryColumn<>(owner, expression));
  }

  private record ColumnSet<E>(
      List<QueryColumn<E, ?>> columns, List<ColumnExpression<E, ?>> selections) {}

  /** Query-private AST node; generated subclasses never see or construct it. */
  static final class TableNode<E> extends TableExpression<E> implements QueryRelation {

    private final QueryTableModel<E> model;
    final QueryTable<E> owner;
    private final EntityRelationSource relationSource;

    private TableNode(QueryTableModel<E> model, EntityMeta<E> entity) {
      super(entity);
      this.model = model;
      this.owner = model.owner;
      this.relationSource = new EntityRelationSource(this);
    }

    private TableNode(QueryTableModel<E> model, EntityMeta<E> entity, Identifier alias) {
      super(entity, alias);
      this.model = model;
      this.owner = model.owner;
      this.relationSource = new EntityRelationSource(this);
    }

    private <V> ColumnExpression<E, V> columnFor(PropertyMeta<E, V> property) {
      return column(property);
    }

    @Override
    public RelationSource compile(QueryConditionCompiler compiler) {
      Objects.requireNonNull(compiler, "compiler");
      return relationSource;
    }

    @Override
    public QueryTable<E> entityTable() {
      return owner;
    }

    @Override
    public TableNode<E> as(Identifier alias) {
      QueryTable<E> aliased = owner.as(Objects.requireNonNull(alias, "alias").value());
      return of(aliased).tableNode;
    }
  }
}
