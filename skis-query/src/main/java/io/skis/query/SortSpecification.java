package io.skis.query;

/** Immutable ordering item over one framework-owned selectable expression. */
public interface SortSpecification {

  /** Returns an equivalent item with null values ordered first. */
  SortSpecification nullsFirst();

  /** Returns an equivalent item with null values ordered last. */
  SortSpecification nullsLast();

  SortDirection direction();

  NullPlacement nullPlacement();
}
