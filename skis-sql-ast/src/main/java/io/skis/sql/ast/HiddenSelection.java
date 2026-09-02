package io.skis.sql.ast;

import java.util.Objects;

/** Internal SELECT item used for execution metadata but excluded from the user row shape. */
public record HiddenSelection(SqlExpression<?> expression, Identifier alias) {

  public HiddenSelection {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(alias, "alias");
  }
}
