package io.skis.sql.ast;

import java.util.Objects;
import java.util.Optional;

/** Relation source adapter that retains an entity {@link TableExpression} by reference. */
public record EntityRelationSource(TableExpression<?> table) implements RelationSource {

  public EntityRelationSource {
    Objects.requireNonNull(table, "table");
  }

  @Override
  public String effectiveQualifier() {
    return table.alias().map(Identifier::value).orElse(table.entity().table().name());
  }

  @Override
  public Optional<TableExpression<?>> entityTable() {
    return Optional.of(table);
  }
}
