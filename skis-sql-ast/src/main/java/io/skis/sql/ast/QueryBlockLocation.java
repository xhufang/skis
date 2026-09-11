package io.skis.sql.ast;

import java.util.Objects;

/** Deterministic structural location of one nested query block inside its parent block. */
public record QueryBlockLocation(QueryClause clause, int itemOrdinal, int nestedOrdinal) {

  public QueryBlockLocation {
    Objects.requireNonNull(clause, "clause");
    if (itemOrdinal < 0) {
      throw new IllegalArgumentException("itemOrdinal must not be negative");
    }
    if (nestedOrdinal < 0) {
      throw new IllegalArgumentException("nestedOrdinal must not be negative");
    }
  }

  /** Location of a nested block within a visible SELECT item. */
  public static QueryBlockLocation select(int itemOrdinal, int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.SELECT, itemOrdinal, nestedOrdinal);
  }

  /** Location of a derived block used as root source 0 or a one-based Join source. */
  public static QueryBlockLocation relationSource(int sourceOrdinal, int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.JOIN_SOURCE, sourceOrdinal, nestedOrdinal);
  }

  /** Location of a nested block within a one-based Join's ON predicate. */
  public static QueryBlockLocation joinOn(int joinOrdinal, int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.JOIN_ON, joinOrdinal, nestedOrdinal);
  }

  /** Location of a nested block within WHERE. */
  public static QueryBlockLocation where(int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.WHERE, 0, nestedOrdinal);
  }

  /** Location of a nested block within one GROUP BY item. */
  public static QueryBlockLocation groupBy(int itemOrdinal, int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.GROUP_BY, itemOrdinal, nestedOrdinal);
  }

  /** Location of a nested block within HAVING. */
  public static QueryBlockLocation having(int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.HAVING, 0, nestedOrdinal);
  }

  /** Location of a nested block within one ORDER BY item. */
  public static QueryBlockLocation orderBy(int itemOrdinal, int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.ORDER_BY, itemOrdinal, nestedOrdinal);
  }

  /** Location of a nested block within one pagination expression. */
  public static QueryBlockLocation pagination(int itemOrdinal, int nestedOrdinal) {
    return new QueryBlockLocation(QueryClause.PAGINATION, itemOrdinal, nestedOrdinal);
  }

  @Override
  public String toString() {
    return clause.name() + '[' + Integer.toString(itemOrdinal) + "]#" + nestedOrdinal;
  }
}
