package io.skis.query;

import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlType;
import java.util.Objects;

/** Explicit typed handle for one publicly named derived-relation output. */
public sealed class DerivedOutput<V> permits NonNullDerivedOutput {

  private final Selectable<V> selectable;
  private final Identifier alias;
  private final Nullability nullability;

  DerivedOutput(Selectable<V> selectable, Identifier alias, Nullability nullability) {
    this.selectable = Objects.requireNonNull(selectable, "selectable");
    this.alias = Objects.requireNonNull(alias, "alias");
    this.nullability = Objects.requireNonNull(nullability, "nullability");
    if (!nullability.isNullable() && selectable.nullability().isNullable()) {
      throw new QueryValidationException(
          "a nullable selectable cannot define a non-null derived output");
    }
  }

  /** Explicit SQL column alias exposed by the derived relation. */
  public final Identifier alias() {
    return alias;
  }

  /** Boxed Java value type of this output. */
  public final Class<V> javaType() {
    return selectable.javaType();
  }

  /** Portable SQL value type of this output. */
  public final SqlType sqlType() {
    return selectable.sqlType();
  }

  /** Declared output nullability, before an outer join can null-extend the relation. */
  public final Nullability nullability() {
    return nullability;
  }

  final Selectable<V> selectable() {
    return selectable;
  }
}
