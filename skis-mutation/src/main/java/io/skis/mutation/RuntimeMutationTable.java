package io.skis.mutation;

import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.TableExpression;

/** Internal metadata-backed table used to compile canonical entity mutation plans. */
final class RuntimeMutationTable<E> extends TableExpression<E> {

  RuntimeMutationTable(EntityMeta<E> entity) {
    super(entity);
  }

  private RuntimeMutationTable(EntityMeta<E> entity, Identifier alias) {
    super(entity, alias);
  }

  <V> ColumnExpression<E, V> expression(PropertyMeta<E, V> property) {
    return column(property);
  }

  @Override
  public RuntimeMutationTable<E> as(Identifier alias) {
    return new RuntimeMutationTable<>(entity(), alias);
  }
}
