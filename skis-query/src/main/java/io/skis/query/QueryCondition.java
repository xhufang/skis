package io.skis.query;

/** Framework-owned immutable SQL condition over one or more selectable expressions. */
public interface QueryCondition {

  /** Returns a new grouped condition combining both operands with SQL {@code AND}. */
  QueryCondition and(QueryCondition other);

  /** Returns a new grouped condition combining both operands with SQL {@code OR}. */
  QueryCondition or(QueryCondition other);

  /** Returns a new grouped condition representing SQL three-valued {@code NOT}. */
  QueryCondition not();
}
