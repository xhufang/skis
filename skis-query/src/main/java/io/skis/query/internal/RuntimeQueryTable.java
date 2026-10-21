package io.skis.query.internal;

import io.skis.metadata.EntityMeta;
import io.skis.query.QueryTable;

/** Internal metadata-backed table used to compile value-independent canonical plans. */
final class RuntimeQueryTable<E> extends QueryTable<E> {

  RuntimeQueryTable(EntityMeta<E> entity) {
    super(entity);
  }

  private RuntimeQueryTable(EntityMeta<E> entity, String alias) {
    super(entity, alias);
  }

  @Override
  public RuntimeQueryTable<E> as(String alias) {
    return new RuntimeQueryTable<>(entity(), alias);
  }
}
