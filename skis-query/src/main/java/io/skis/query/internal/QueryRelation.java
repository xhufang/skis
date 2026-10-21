package io.skis.query.internal;

import io.skis.query.DerivedRelation;
import io.skis.query.QueryTable;
import io.skis.sql.ast.RelationSource;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Package-owned relation identity used by immutable query construction state. */
interface QueryRelation {

  RelationSource compile(QueryConditionCompiler compiler);

  @Nullable QueryTable<?> entityTable();
}

/** Internal conversions keep public tables and derived relations free of internal supertypes. */
final class QueryRelations {

  private QueryRelations() {}

  static QueryRelation of(QueryTable<?> table) {
    return QueryTableModel.of(Objects.requireNonNull(table, "table")).relation();
  }

  static QueryRelation of(DerivedRelation relation) {
    return QueryModels.relation(Objects.requireNonNull(relation, "relation")).queryRelation();
  }

  static RelationSource compile(QueryRelation relation, QueryConditionCompiler compiler) {
    return Objects.requireNonNull(relation, "relation")
        .compile(Objects.requireNonNull(compiler, "compiler"));
  }
}

/** One derived-relation occurrence without leaking an internal supertype through the public API. */
final class DerivedQueryRelation implements QueryRelation {

  private final DerivedRelation relation;

  DerivedQueryRelation(DerivedRelation relation) {
    this.relation = Objects.requireNonNull(relation, "relation");
  }

  @Override
  public RelationSource compile(QueryConditionCompiler compiler) {
    return QueryModels.relation(relation).compile(Objects.requireNonNull(compiler, "compiler"));
  }

  @Override
  public @Nullable QueryTable<?> entityTable() {
    return null;
  }
}
