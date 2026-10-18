package io.skis.query;

import io.skis.metadata.PropertyMeta;
import io.skis.query.spi.ProjectionMapping;
import io.skis.query.spi.ProjectionSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resolves immutable special-projection templates against one complete query block. */
final class ProjectionSourceBinder {

  private ProjectionSourceBinder() {}

  static <R> ProjectionSelection<R> bind(
      ProjectionType<R> projection, QueryRelation root, List<QueryJoin> joins) {
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(joins, "joins");
    ProjectionMapping<R> mapping =
        Objects.requireNonNull(projection.mapping(), "projection mapping");
    List<ProjectionSource> sources = List.copyOf(projection.sources());
    if (sources.size() != mapping.parameters().size()) {
      throw new QueryValidationException(
          "special projection '"
              + mapping.resultType().getTypeName()
              + "' declares "
              + sources.size()
              + " sources for "
              + mapping.parameters().size()
              + " constructor parameters");
    }
    List<QueryTable<?>> tables = entityTables(root, joins);
    List<Selectable<?>> selections = new ArrayList<>(sources.size());
    for (int ordinal = 0; ordinal < sources.size(); ordinal++) {
      ProjectionSource source = Objects.requireNonNull(sources.get(ordinal), "projection source");
      selections.add(resolve(mapping, ordinal, source, tables));
    }
    return ProjectionSelection.generated(mapping, selections);
  }

  private static List<QueryTable<?>> entityTables(QueryRelation root, List<QueryJoin> joins) {
    List<QueryTable<?>> tables = new ArrayList<>(joins.size() + 1);
    QueryTable<?> rootTable = root.entityTable();
    if (rootTable != null) {
      tables.add(rootTable);
    }
    for (QueryJoin join : joins) {
      QueryTable<?> table = join.right().entityTable();
      if (table != null) {
        tables.add(table);
      }
    }
    return List.copyOf(tables);
  }

  private static Selectable<?> resolve(
      ProjectionMapping<?> mapping,
      int ordinal,
      ProjectionSource source,
      List<QueryTable<?>> tables) {
    PropertyMeta<?, ?> property = source.property();
    if (source.alias().isPresent()) {
      String alias = source.alias().orElseThrow();
      QueryTable<?> matched = null;
      int matches = 0;
      for (QueryTable<?> table : tables) {
        if (table.alias().filter(alias::equals).isPresent()) {
          matched = table;
          matches++;
        }
      }
      if (matches == 0) {
        throw sourceFailure(
            mapping, ordinal, property, "has no visible table with alias '" + alias + "'");
      }
      if (matches > 1) {
        throw sourceFailure(
            mapping, ordinal, property, "matches multiple tables with alias '" + alias + "'");
      }
      QueryTable<?> table = Objects.requireNonNull(matched, "matched table");
      if (!owns(table, property)) {
        throw sourceFailure(
            mapping,
            ordinal,
            property,
            "uses alias '"
                + alias
                + "' for entity '"
                + table.entity().javaType().getName()
                + "', which does not declare that property source");
      }
      return column(table, property);
    }

    QueryTable<?> matched = null;
    int matches = 0;
    for (QueryTable<?> table : tables) {
      if (owns(table, property)) {
        matched = table;
        matches++;
      }
    }
    if (matches == 0) {
      throw sourceFailure(
          mapping, ordinal, property, "has no matching entity table in the final query block");
    }
    if (matches > 1) {
      throw sourceFailure(
          mapping,
          ordinal,
          property,
          "is ambiguous across "
              + matches
              + " occurrences; declare @PropertyFrom(alias = ...) explicitly");
    }
    return column(Objects.requireNonNull(matched, "matched table"), property);
  }

  private static boolean owns(QueryTable<?> table, PropertyMeta<?, ?> property) {
    int ordinal = property.ordinal();
    return ordinal >= 0
        && ordinal < table.entity().properties().size()
        && table.entity().properties().get(ordinal) == property;
  }

  @SuppressWarnings("unchecked")
  private static <E> Selectable<?> column(QueryTable<E> table, PropertyMeta<?, ?> property) {
    return table.internalColumn((PropertyMeta<E, ?>) property);
  }

  private static QueryValidationException sourceFailure(
      ProjectionMapping<?> mapping, int ordinal, PropertyMeta<?, ?> property, String reason) {
    ProjectionMapping.Parameter parameter = mapping.parameters().get(ordinal);
    return new QueryValidationException(
        "special projection '"
            + mapping.resultType().getTypeName()
            + "' parameter #"
            + (ordinal + 1)
            + " '"
            + parameter.name()
            + "' source property '"
            + property.name()
            + "' "
            + reason);
  }
}
