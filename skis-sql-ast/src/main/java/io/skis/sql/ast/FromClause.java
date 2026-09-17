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
  private final IdentityHashMap<DerivedRelationReference, Boolean> nullExtendedDerivedRelations;

  /** Creates a FROM clause and assigns stable occurrence ordinals in declaration order. */
  public FromClause(RelationSource root, List<JoinClause> joins) {
    this.root = Objects.requireNonNull(root, "root");
    this.joins = List.copyOf(Objects.requireNonNull(joins, "joins"));
    this.occurrences = createOccurrences(root, this.joins);
    this.nullExtendedTables = EffectiveNullabilityResolver.finalTableState(this);
    this.nullExtendedDerivedRelations =
        EffectiveNullabilityResolver.finalDerivedRelationState(this);
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

  /** Resolves a derived relation occurrence by its framework-owned reference identity. */
  public Optional<TableOccurrence> occurrenceOf(DerivedRelationReference reference) {
    Objects.requireNonNull(reference, "reference");
    for (TableOccurrence occurrence : occurrences) {
      if (occurrence.source().derivedReference().orElse(null) == reference) {
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
    return EffectiveNullabilityResolver.resolve(
        expression, nullExtendedTables, nullExtendedDerivedRelations);
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
    IdentityHashMap<DerivedRelationReference, Integer> ordinalsByDerivedReference =
        new IdentityHashMap<>();
    Map<String, Integer> ordinalsByQualifier = new HashMap<>();
    addOccurrence(
        root,
        0,
        result,
        ordinalsBySource,
        ordinalsByReference,
        ordinalsByDerivedReference,
        ordinalsByQualifier);
    for (int index = 0; index < joins.size(); index++) {
      addOccurrence(
          joins.get(index).right(),
          index + 1,
          result,
          ordinalsBySource,
          ordinalsByReference,
          ordinalsByDerivedReference,
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
      IdentityHashMap<DerivedRelationReference, Integer> ordinalsByDerivedReference,
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
    Optional<DerivedRelationReference> derivedReference = source.derivedReference();
    if (derivedReference.isPresent()) {
      Integer previousReference =
          ordinalsByDerivedReference.put(derivedReference.orElseThrow(), ordinal);
      if (previousReference != null) {
        throw new IllegalArgumentException(
            "derived relation reference with alias '"
                + derivedReference.orElseThrow().alias().value()
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
    Optional<DerivedRelationReference> derivedReference = source.derivedReference();
    if (derivedReference.isPresent()) {
      return new IllegalArgumentException(
          "derived relation reference with alias '"
              + derivedReference.orElseThrow().alias().value()
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
    if (firstTable.isPresent()
        && secondTable.isPresent()
        && firstTable.orElseThrow() == secondTable.orElseThrow()) {
      return true;
    }
    Optional<DerivedRelationReference> firstDerived = first.derivedReference();
    Optional<DerivedRelationReference> secondDerived = second.derivedReference();
    return firstDerived.isPresent()
        && secondDerived.isPresent()
        && firstDerived.orElseThrow() == secondDerived.orElseThrow();
  }
}
