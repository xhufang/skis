package io.skis.query;

import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.PropertyRuntime;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.TableOccurrence;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Immutable query-local mapping from stable table occurrences to generated runtime models. */
final class TableRuntimeScope {

  private final FromClause fromClause;
  private final List<Occurrence<?>> occurrences;
  private final IdentityHashMap<TableExpression<?>, Integer> ordinalsByTable;

  private TableRuntimeScope(
      FromClause fromClause,
      List<Occurrence<?>> occurrences,
      IdentityHashMap<TableExpression<?>, Integer> ordinalsByTable) {
    this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
    this.occurrences = List.copyOf(occurrences);
    this.ordinalsByTable = ordinalsByTable;
  }

  static TableRuntimeScope resolve(
      EntityRuntimeRegistry registry, FromClause fromClause) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(fromClause, "fromClause");
    List<Occurrence<?>> occurrences = new ArrayList<>(fromClause.occurrences().size());
    IdentityHashMap<TableExpression<?>, Integer> indexed = new IdentityHashMap<>();
    for (TableOccurrence occurrence : fromClause.occurrences()) {
      if (!(occurrence.table() instanceof QueryTable<?> table)) {
        throw new QueryValidationException(
            "table occurrence #"
                + occurrence.occurrenceOrdinal()
                + " is not backed by a query table");
      }
      Occurrence<?> resolved = resolveOccurrence(registry, occurrence.occurrenceOrdinal(), table);
      occurrences.add(resolved);
      indexed.put(table, occurrence.occurrenceOrdinal());
    }
    return new TableRuntimeScope(fromClause, occurrences, indexed);
  }

  <E> Occurrence<E> require(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    Integer ordinal = ordinalsByTable.get(table);
    if (ordinal == null) {
      throw new QueryValidationException(
          "table entity '"
              + table.entity().entityName()
              + "'"
              + table.alias().map(alias -> " with alias '" + alias.value() + "'").orElse("")
              + " is not visible in the final query scope");
    }
    return castOccurrence(occurrences.get(ordinal));
  }

  <E, V> PropertyRuntime<E, V> property(QueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    Occurrence<E> occurrence = require(column.table());
    try {
      return occurrence.model().property(column.property());
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(
          "property '"
              + column.property().name()
              + "' does not match "
              + occurrence.description(),
          failure);
    }
  }

  Nullability effectiveNullability(QueryColumn<?, ?> column) {
    Objects.requireNonNull(column, "column");
    require(column.table());
    return fromClause.effectiveNullability(column.expression());
  }

  boolean isNullExtended(QueryTable<?> table) {
    require(table);
    return fromClause.isNullExtended(table);
  }

  private static <E> Occurrence<E> resolveOccurrence(
      EntityRuntimeRegistry registry, int ordinal, QueryTable<E> table) {
    try {
      return new Occurrence<>(ordinal, table, registry.require(table.entity()));
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(
          "cannot resolve runtime model for table occurrence #"
              + ordinal
              + " entity '"
              + table.entity().entityName()
              + "'"
              + table.alias().map(alias -> " with alias '" + alias.value() + "'").orElse(""),
          failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static <E> Occurrence<E> castOccurrence(Occurrence<?> occurrence) {
    return (Occurrence<E>) occurrence;
  }

  record Occurrence<E>(
      int occurrenceOrdinal, QueryTable<E> table, EntityRuntimeModel<E> model) {

    Occurrence {
      if (occurrenceOrdinal < 0) {
        throw new IllegalArgumentException("occurrence ordinal must not be negative");
      }
      Objects.requireNonNull(table, "table");
      Objects.requireNonNull(model, "model");
      if (table.entity() != model.entity()) {
        throw new IllegalArgumentException(
            "table occurrence does not use its runtime model's canonical metadata");
      }
    }

    String description() {
      return "table occurrence #"
          + occurrenceOrdinal
          + " entity '"
          + table.entity().entityName()
          + "'"
          + table.alias().map(alias -> " with alias '" + alias.value() + "'").orElse("");
    }
  }
}
