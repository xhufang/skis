package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.query.DerivedRelation;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.TableOccurrence;
import java.util.IdentityHashMap;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query-local source bindings and a lazy compilation-only memo of output Codecs. */
final class TableRuntimeScope {

  private final EntityRuntimeRegistry registry;
  private final FromClause fromClause;
  private final IdentityHashMap<TableExpression<?>, Occurrence<?>> occurrencesByTable;
  private final IdentityHashMap<DerivedRelationReference, Integer> ordinalsByDerivedRelation;
  private final @Nullable TableRuntimeScope codecOwner;
  private @Nullable IdentityHashMap<Object, JdbcTypeCodec<?>> outputCodecs;

  private TableRuntimeScope(
      EntityRuntimeRegistry registry,
      FromClause fromClause,
      IdentityHashMap<TableExpression<?>, Occurrence<?>> occurrencesByTable,
      IdentityHashMap<DerivedRelationReference, Integer> ordinalsByDerivedRelation,
      @Nullable TableRuntimeScope codecOwner) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
    this.occurrencesByTable = occurrencesByTable;
    this.ordinalsByDerivedRelation = ordinalsByDerivedRelation;
    this.codecOwner = codecOwner;
  }

  static TableRuntimeScope resolve(EntityRuntimeRegistry registry, FromClause fromClause) {
    return resolve(registry, fromClause, null);
  }

  private static TableRuntimeScope resolve(
      EntityRuntimeRegistry registry,
      FromClause fromClause,
      @Nullable TableRuntimeScope codecOwner) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(fromClause, "fromClause");
    IdentityHashMap<TableExpression<?>, Occurrence<?>> entities = new IdentityHashMap<>();
    IdentityHashMap<DerivedRelationReference, Integer> derived = new IdentityHashMap<>();
    for (TableOccurrence occurrence : fromClause.occurrences()) {
      TableExpression<?> sourceTable = occurrence.entityTable().orElse(null);
      if (sourceTable != null) {
        QueryTable<?> table = QueryTableModel.ownerOf(sourceTable);
        if (table == null) {
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_EXPRESSION_IDENTITY,
              "table occurrence #"
                  + occurrence.occurrenceOrdinal()
                  + " is not backed by a query table");
        }
        Occurrence<?> resolved = resolveOccurrence(registry, occurrence.occurrenceOrdinal(), table);
        entities.put(sourceTable, resolved);
        continue;
      }
      DerivedRelationReference reference = occurrence.derivedReference().orElse(null);
      if (reference != null) {
        derived.put(reference, occurrence.occurrenceOrdinal());
        continue;
      }
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_EXPRESSION_IDENTITY,
          "relation occurrence #"
              + occurrence.occurrenceOrdinal()
              + " has no supported runtime source kind");
    }
    return new TableRuntimeScope(registry, fromClause, entities, derived, codecOwner);
  }

  /** Reuses resolved runtime models when a validation copy has the same source occurrences. */
  TableRuntimeScope forFromClause(FromClause candidate) {
    Objects.requireNonNull(candidate, "candidate");
    if (candidate == fromClause) {
      return this;
    }
    for (TableOccurrence occurrence : candidate.occurrences()) {
      TableExpression<?> sourceTable = occurrence.entityTable().orElse(null);
      if (sourceTable != null) {
        Occurrence<?> resolved = occurrencesByTable.get(sourceTable);
        if (resolved == null || resolved.occurrenceOrdinal() != occurrence.occurrenceOrdinal()) {
          return resolve(registry, candidate, codecOwner == null ? this : codecOwner);
        }
        continue;
      }
      DerivedRelationReference reference = occurrence.derivedReference().orElse(null);
      Integer ordinal = reference == null ? null : ordinalsByDerivedRelation.get(reference);
      if (ordinal == null || ordinal != occurrence.occurrenceOrdinal()) {
        return resolve(registry, candidate, codecOwner == null ? this : codecOwner);
      }
    }
    if (candidate.occurrences().size()
        != occurrencesByTable.size() + ordinalsByDerivedRelation.size()) {
      return resolve(registry, candidate, codecOwner == null ? this : codecOwner);
    }
    return new TableRuntimeScope(
        registry,
        candidate,
        occurrencesByTable,
        ordinalsByDerivedRelation,
        codecOwner == null ? this : codecOwner);
  }

  JdbcTypeCodec<?> outputCodec(Selectable<?> selectable) {
    if (selectable instanceof QueryColumn<?, ?>) {
      return ResolvedValueMapping.codecFor(selectable, registry);
    }
    if (codecOwner != null) {
      return codecOwner.outputCodec(selectable);
    }
    IdentityHashMap<Object, JdbcTypeCodec<?>> codecs = outputCodecs;
    if (codecs == null) {
      codecs = new IdentityHashMap<>();
      outputCodecs = codecs;
    }
    return ResolvedValueMapping.outputCodec(selectable, registry, codecs);
  }

  Nullability effectiveNullability(SqlExpression<?> expression) {
    try {
      return fromClause.effectiveNullability(expression);
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_SOURCE_OUT_OF_SCOPE,
          failure,
          "standard expression references a source outside the result scope");
    }
  }

  <E> Occurrence<E> require(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    Occurrence<?> occurrence = occurrencesByTable.get(QueryTableModel.of(table).tableExpression());
    if (occurrence == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_SOURCE_OUT_OF_SCOPE,
          "table entity '"
              + table.entity().entityName()
              + "'"
              + table.alias().map(alias -> " with alias '" + alias + "'").orElse("")
              + " is not visible in the final query scope");
    }
    return castOccurrence(occurrence);
  }

  <E, V> PropertyRuntime<E, V> property(QueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    Occurrence<E> occurrence = require(column.table());
    try {
      return occurrence.model().property(column.property());
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_EXPRESSION_IDENTITY,
          "property '" + column.property().name() + "' does not match " + occurrence.description(),
          failure);
    }
  }

  Nullability effectiveNullability(QueryColumn<?, ?> column) {
    Objects.requireNonNull(column, "column");
    require(column.table());
    return fromClause.effectiveNullability(column.expression());
  }

  int require(DerivedRelation relation) {
    Objects.requireNonNull(relation, "relation");
    Integer ordinal = ordinalsByDerivedRelation.get(QueryModels.relation(relation).reference());
    if (ordinal == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_SOURCE_OUT_OF_SCOPE,
          "derived relation alias '"
              + relation.alias()
              + "' is not visible in the final query scope");
    }
    return ordinal;
  }

  Nullability effectiveNullability(DerivedColumnSelectable<?> column) {
    Objects.requireNonNull(column, "column");
    require(column.relation());
    return fromClause.effectiveNullability(column.expression());
  }

  boolean isNullExtended(QueryTable<?> table) {
    require(table);
    return fromClause.isNullExtended(QueryTableModel.of(table).tableExpression());
  }

  private static <E> Occurrence<E> resolveOccurrence(
      EntityRuntimeRegistry registry, int ordinal, QueryTable<E> table) {
    try {
      return new Occurrence<>(ordinal, table, registry.require(table.entity()));
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_METADATA_REQUIRED,
          "cannot resolve runtime model for table occurrence #"
              + ordinal
              + " entity '"
              + table.entity().entityName()
              + "'"
              + table.alias().map(alias -> " with alias '" + alias + "'").orElse(""),
          failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static <E> Occurrence<E> castOccurrence(Occurrence<?> occurrence) {
    return (Occurrence<E>) occurrence;
  }

  record Occurrence<E>(int occurrenceOrdinal, QueryTable<E> table, EntityRuntimeModel<E> model) {

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
          + table.alias().map(alias -> " with alias '" + alias + "'").orElse("");
    }
  }
}
