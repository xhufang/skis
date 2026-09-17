package io.skis.query;

import io.skis.sql.ast.EntityRelationSource;
import io.skis.sql.ast.RelationSource;
import java.util.Objects;

/** Package-owned common source contract for entity tables and derived relations. */
sealed interface QueryRelation permits QueryTable, DerivedRelation {}

/** Compiles one query-layer relation through the statement-wide parameter layout. */
final class QueryRelations {

  private QueryRelations() {}

  static RelationSource compile(QueryRelation relation, QueryConditionCompiler compiler) {
    Objects.requireNonNull(compiler, "compiler");
    return switch (Objects.requireNonNull(relation, "relation")) {
      case QueryTable<?> table -> new EntityRelationSource(table);
      case DerivedRelation derived -> derived.compile(compiler);
    };
  }
}
