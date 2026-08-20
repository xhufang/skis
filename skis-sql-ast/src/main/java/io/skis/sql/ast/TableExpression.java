package io.skis.sql.ast;

import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable typed reference to an entity table in a SQL statement. */
public abstract class TableExpression<E> {

  private final EntityMeta<E> entity;
  private final @Nullable Identifier alias;

  /** Creates an unaliased table expression. */
  protected TableExpression(EntityMeta<E> entity) {
    this.entity = Objects.requireNonNull(entity, "entity");
    this.alias = null;
  }

  /** Creates an aliased table expression from an already validated identifier. */
  protected TableExpression(EntityMeta<E> entity, Identifier alias) {
    this.entity = Objects.requireNonNull(entity, "entity");
    this.alias = Objects.requireNonNull(alias, "alias");
  }

  public final EntityMeta<E> entity() {
    return entity;
  }

  /** Returns the validated alias, or an empty optional for an unaliased table. */
  public final Optional<Identifier> alias() {
    return Optional.ofNullable(alias);
  }

  /** Returns an independently aliased table reference after validating DSL string input. */
  public TableExpression<E> as(String alias) {
    return as(Identifier.of(alias));
  }

  /** Returns an independently aliased table reference using a validated identifier. */
  public abstract TableExpression<E> as(Identifier alias);

  /** Creates a column expression owned by this table reference. */
  protected final <V> ColumnExpression<E, V> column(PropertyMeta<E, V> property) {
    Objects.requireNonNull(property, "property");
    int ordinal = property.ordinal();
    if (ordinal >= entity.properties().size() || entity.properties().get(ordinal) != property) {
      throw new IllegalArgumentException(
          "property '" + property.name() + "' does not belong to entity '" + entity.entityName() + "'");
    }
    return new ColumnExpression<>(this, property);
  }

}
