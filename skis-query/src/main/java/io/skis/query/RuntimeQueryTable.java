package io.skis.query;

import io.skis.metadata.EntityMeta;
import io.skis.sql.ast.Identifier;

/** Internal metadata-backed table used to compile value-independent canonical plans. */
final class RuntimeQueryTable<E> extends QueryTable<E> {

  RuntimeQueryTable(EntityMeta<E> entity) {
    super(entity);
  }

  private RuntimeQueryTable(EntityMeta<E> entity, Identifier alias) {
    super(entity, alias);
  }

  @Override
  public RuntimeQueryTable<E> as(Identifier alias) {
    return new RuntimeQueryTable<>(entity(), alias);
  }
}
