package io.skis.sql.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable root relation and ordered, left-deep joins for one query block. */
public final class FromClause {

  private final RelationSource root;
  private final List<JoinClause> joins;
  private final List<TableOccurrence> occurrences;
  private final IdentityHashMap<TableExpression<?>, Boolean> nullExtendedTables;
  private final IdentityHashMap<RelationSource, Boolean> nullExtendedSources;

  /** Creates a FROM clause and assigns stable occurrence ordinals in declaration order. */
  public FromClause(RelationSource root, List<JoinClause> joins) {
    this.root = Objects.requireNonNull(root, "root");
    this.joins = List.copyOf(Objects.requireNonNull(joins, "joins"));
    this.occurrences = createOccurrences(root, this.joins);
    this.nullExtendedSources = EffectiveNullabilityResolver.finalSourceState(this);
    this.nullExtendedTables = EffectiveNullabilityResolver.finalTableState(this);
  }

  /** Creates a FROM clause by adapting the entity root without losing its object identity. */
  public FromClause(TableExpression<?> root, List<JoinClause> joins) {
    this(RelationSource.entity(root), joins);
  }

  /** Creates a single-relation FROM clause. */
  public static FromClause of(RelationSource root) {
    return new FromClause(root, List.of());
  }

  /** Creates a single-table FROM clause. */
  public static FromClause of(TableExpression<?> root) {
    return new FromClause(root, List.of());
  }

  public RelationSource root() {
    return root;
  }

  public List<JoinClause> joins() {
    return joins;
  }

  /** Returns all relation occurrences, with the root at ordinal 0. */
  public List<TableOccurrence> occurrences() {
    return occurrences;
  }

  /** Resolves a DSL table reference by object identity, never by structural equality. */
  public Optional<TableOccurrence> occurrenceOf(TableExpression<?> table) {
    Objects.requireNonNull(table, "table");
    for (TableOccurrence occurrence : occurrences) {
      if (occurrence.entityTable().orElse(null) == table) {
        return Optional.of(occurrence);
      }
    }
    return Optional.empty();
  }

  /** Resolves a relation source by its framework-owned reference identity. */
  public Optional<TableOccurrence> occurrenceOf(RelationSource source) {
    Objects.requireNonNull(source, "source");
    for (TableOccurrence occurrence : occurrences) {
      if (sameReference(occurrence.source(), source)) {
        return Optional.of(occurrence);
      }
    }
    return Optional.empty();
  }

  /** Returns whether outer joins can replace this relation occurrence with an all-NULL row. */
  public boolean isNullExtended(RelationSource source) {
    Objects.requireNonNull(source, "source");
    TableOccurrence occurrence =
        occurrenceOf(source)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "relation source is not visible in this FROM clause"));
    Boolean nullable = nullExtendedSources.get(occurrence.source());
    if (nullable == null) {
      throw new IllegalStateException("relation occurrence has no null-extension state");
    }
    return nullable;
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
      RelationSource root, List<JoinClause> joins) {
    List<TableOccurrence> result = new ArrayList<>(joins.size() + 1);
    IdentityHashMap<RelationSource, Integer> ordinalsBySource = new IdentityHashMap<>();
    IdentityHashMap<TableExpression<?>, Integer> ordinalsByReference = new IdentityHashMap<>();
    Map<String, Integer> ordinalsByQualifier = new HashMap<>();
    addOccurrence(
        root, 0, result, ordinalsBySource, ordinalsByReference, ordinalsByQualifier);
    for (int index = 0; index < joins.size(); index++) {
      addOccurrence(
          joins.get(index).right(),
          index + 1,
          result,
          ordinalsBySource,
          ordinalsByReference,
          ordinalsByQualifier);
    }
    return List.copyOf(result);
  }

  private static void addOccurrence(
      RelationSource source,
      int ordinal,
      List<TableOccurrence> occurrences,
      IdentityHashMap<RelationSource, Integer> ordinalsBySource,
      IdentityHashMap<TableExpression<?>, Integer> ordinalsByReference,
      Map<String, Integer> ordinalsByQualifier) {
    Objects.requireNonNull(source, "source");
    Integer previousSource = ordinalsBySource.put(source, ordinal);
    if (previousSource != null) {
      throw duplicateSource(source, previousSource, ordinal);
    }
    Optional<TableExpression<?>> entityTable = source.entityTable();
    if (entityTable.isPresent()) {
      TableExpression<?> table = entityTable.orElseThrow();
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
    }
    String qualifier = source.effectiveQualifier();
    Integer previousQualifier = ordinalsByQualifier.putIfAbsent(qualifier, ordinal);
    if (previousQualifier != null) {
      throw new IllegalArgumentException(
          "effective table qualifier '"
              + qualifier
              + "' is duplicated in the same FROM clause at occurrences #"
              + previousQualifier
              + " and #"
              + ordinal);
    }
    occurrences.add(new TableOccurrence(ordinal, source));
  }

  private static IllegalArgumentException duplicateSource(
      RelationSource source, int previousOrdinal, int ordinal) {
    Optional<TableExpression<?>> entityTable = source.entityTable();
    if (entityTable.isPresent()) {
      return new IllegalArgumentException(
          "table expression for entity '"
              + entityTable.orElseThrow().entity().entityName()
              + "' is registered more than once in the same FROM clause at occurrences #"
              + previousOrdinal
              + " and #"
              + ordinal);
    }
    return new IllegalArgumentException(
        "relation source is registered more than once in the same FROM clause at occurrences #"
            + previousOrdinal
            + " and #"
            + ordinal);
  }

  private static boolean sameReference(RelationSource first, RelationSource second) {
    if (first == second) {
      return true;
    }
    Optional<TableExpression<?>> firstTable = first.entityTable();
    Optional<TableExpression<?>> secondTable = second.entityTable();
    return firstTable.isPresent()
        && secondTable.isPresent()
        && firstTable.orElseThrow() == secondTable.orElseThrow();
  }
}
