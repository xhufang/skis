package io.skis.sql.ast;

import java.util.Optional;

/**
 * Framework-owned relation source for one occurrence in a {@link FromClause}.
 *
 * <p>The sealed hierarchy keeps source kinds traversable by semantic validation and dialect
 * rendering. Entity tables are adapted through {@link EntityRelationSource}; later source kinds
 * can therefore share the FROM/Join structure without pretending to be entity metadata.
 */
public sealed interface RelationSource permits EntityRelationSource {

  /** Adapts an entity table while retaining the exact table-expression reference. */
  static RelationSource entity(TableExpression<?> table) {
    return new EntityRelationSource(table);
  }

  /** Effective SQL qualifier used to detect collisions inside one query block. */
  String effectiveQualifier();

  /** Returns the adapted entity table, or empty for a non-entity relation source. */
  default Optional<TableExpression<?>> entityTable() {
    return Optional.empty();
  }
}
