package io.skis.sql.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable root table and ordered, left-deep joins for one query block. */
public final class FromClause {

  private final TableExpression<?> root;
  private final List<JoinClause> joins;
  private final List<TableOccurrence> occurrences;
  private final IdentityHashMap<TableExpression<?>, Boolean> nullExtendedTables;

  /** Creates a FROM clause and assigns stable occurrence ordinals in declaration order. */
  public FromClause(TableExpression<?> root, List<JoinClause> joins) {
    this.root = Objects.requireNonNull(root, "root");
    this.joins = List.copyOf(Objects.requireNonNull(joins, "joins"));
    this.occurrences = createOccurrences(root, this.joins);
    this.nullExtendedTables = EffectiveNullabilityResolver.finalTableState(this);
  }

  /** Creates a single-table FROM clause. */
  public static FromClause of(TableExpression<?> root) {
    return new FromClause(root, List.of());
  }

  public TableExpression<?> root() {
    return root;
  }

  public List<JoinClause> joins() {
    return joins;
  }

  /** Returns all table occurrences, with the root at ordinal 0. */
  public List<TableOccurrence> occurrences() {
    return occurrences;
  }

  /** Resolves a DSL table reference by object identity, never by structural equality. */
  public Optional<TableOccurrence> occurrenceOf(TableExpression<?> table) {
    Objects.requireNonNull(table, "table");
    for (TableOccurrence occurrence : occurrences) {
      if (occurrence.table() == table) {
        return Optional.of(occurrence);
      }
    }
    return Optional.empty();
  }

  /** Returns whether outer joins can replace this table occurrence with an all-NULL row. */
  public boolean isNullExtended(TableExpression<?> table) {
    Objects.requireNonNull(table, "table");
    Boolean nullable = nullExtendedTables.get(table);
    if (nullable == null) {
      throw new IllegalArgumentException("table expression is not visible in this FROM clause");
    }
    return nullable;
  }

  /** Resolves an expression's effective nullability after every join in this FROM clause. */
  public Nullability effectiveNullability(SqlExpression<?> expression) {
    return EffectiveNullabilityResolver.resolve(expression, nullExtendedTables);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof FromClause clause
            && root.equals(clause.root)
            && joins.equals(clause.joins);
  }

  @Override
  public int hashCode() {
    return 31 * root.hashCode() + joins.hashCode();
  }

  private static List<TableOccurrence> createOccurrences(
      TableExpression<?> root, List<JoinClause> joins) {
    List<TableOccurrence> result = new ArrayList<>(joins.size() + 1);
    IdentityHashMap<TableExpression<?>, Integer> ordinalsByReference = new IdentityHashMap<>();
    Map<String, Integer> ordinalsByQualifier = new HashMap<>();
    addOccurrence(root, 0, result, ordinalsByReference, ordinalsByQualifier);
    for (int index = 0; index < joins.size(); index++) {
      addOccurrence(
          joins.get(index).right(), index + 1, result, ordinalsByReference, ordinalsByQualifier);
    }
    return List.copyOf(result);
  }

  private static void addOccurrence(
      TableExpression<?> table,
      int ordinal,
      List<TableOccurrence> occurrences,
      IdentityHashMap<TableExpression<?>, Integer> ordinalsByReference,
      Map<String, Integer> ordinalsByQualifier) {
    Integer previousReference = ordinalsByReference.put(table, ordinal);
    if (previousReference != null) {
      throw new IllegalArgumentException(
          "table expression for entity '"
              + table.entity().entityName()
              + "' is registered more than once in the same FROM clause at occurrences #"
              + previousReference
              + " and #"
              + ordinal);
    }
    String qualifier = table.alias().map(Identifier::value).orElse(table.entity().table().name());
    Integer previousQualifier = ordinalsByQualifier.putIfAbsent(qualifier, ordinal);
    if (previousQualifier != null) {
      TableExpression<?> previousTable = occurrences.get(previousQualifier).table();
      throw new IllegalArgumentException(
          "effective table qualifier '"
              + qualifier
              + "' is duplicated in the same FROM clause by entity '"
              + previousTable.entity().entityName()
              + "' at occurrence #"
              + previousQualifier
              + " and entity '"
              + table.entity().entityName()
              + "' at occurrence #"
              + ordinal);
    }
    occurrences.add(new TableOccurrence(ordinal, table));
  }
}
