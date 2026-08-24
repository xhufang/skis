package io.skis.query;

import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.TableExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Base class for generated entity tables exposed by the query DSL. */
public abstract class QueryTable<E> extends TableExpression<E> {

  private final List<ColumnExpression<E, ?>> selections;

  /** Creates an unaliased generated query table. */
  protected QueryTable(EntityMeta<E> entity) {
    super(entity);
    this.selections = createSelections(entity);
  }

  /** Creates an independently aliased generated query table. */
  protected QueryTable(EntityMeta<E> entity, Identifier alias) {
    super(entity, alias);
    this.selections = createSelections(entity);
  }

  /** Returns the query-column wrapper for canonical generated property metadata. */
  protected final <V> QueryColumn<E, V> queryColumn(PropertyMeta<E, V> property) {
    return new QueryColumn<>(expression(property));
  }

  List<ColumnExpression<E, ?>> selections() {
    return selections;
  }

  @SuppressWarnings("unchecked")
  <V> ColumnExpression<E, V> expression(PropertyMeta<E, V> property) {
    Objects.requireNonNull(property, "property");
    int ordinal = property.ordinal();
    if (ordinal < 0
        || ordinal >= selections.size()
        || selections.get(ordinal).property() != property) {
      throw new IllegalArgumentException(
          "property '"
              + property.name()
              + "' does not belong to query table '"
              + entity().entityName()
              + "'");
    }
    return (ColumnExpression<E, V>) selections.get(ordinal);
  }

  private List<ColumnExpression<E, ?>> createSelections(EntityMeta<E> entity) {
    List<ColumnExpression<E, ?>> result = new ArrayList<>(entity.properties().size());
    for (PropertyMeta<E, ?> property : entity.properties()) {
      result.add(createExpression(property));
    }
    return List.copyOf(result);
  }

  private <V> ColumnExpression<E, V> createExpression(PropertyMeta<E, V> property) {
    return column(property);
  }
}
