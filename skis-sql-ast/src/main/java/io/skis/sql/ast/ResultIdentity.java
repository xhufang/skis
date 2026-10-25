package io.skis.sql.ast;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.metadata.PropertyMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Conservative final-row identity, derived from an already resolved query occurrence. */
final class ResultIdentity {

  private static final ResultIdentity SINGLETON = new ResultIdentity(List.of(), true, null);

  private final List<ResolvedStructureKey> components;
  // Whether the base identity tuple may be entirely NULL, rather than each column separately.
  private final boolean nullable;
  private final @Nullable String failure;
  private final List<ResolvedStructureKey> presence;
  private final List<List<ResolvedStructureKey>> existenceRequirements;
  private final Map<ResolvedStructureKey, ResolvedStructureKey> equivalentOutputs;

  private ResultIdentity(
      List<ResolvedStructureKey> components, boolean nullable, @Nullable String failure) {
    this(components, nullable, failure, List.of(), List.of(), Map.of());
  }

  private ResultIdentity(
      List<ResolvedStructureKey> components,
      boolean nullable,
      @Nullable String failure,
      List<ResolvedStructureKey> presence,
      List<List<ResolvedStructureKey>> existenceRequirements,
      Map<ResolvedStructureKey, ResolvedStructureKey> equivalentOutputs) {
    this.components = List.copyOf(components);
    this.nullable = nullable;
    this.failure = failure;
    this.presence = List.copyOf(presence);
    this.existenceRequirements = List.copyOf(existenceRequirements);
    this.equivalentOutputs = Map.copyOf(equivalentOutputs);
  }

  /** Resolves identity without reanalyzing expressions or inspecting parameter values. */
  static ResultIdentity resolve(SelectStatement statement, QueryBlockAnalysis analysis) {
    if (analysis.atMostOneRow()) {
      return SINGLETON;
    }
    if (analysis.isGrouped()) {
      List<ResolvedExpression> groups = expressions(analysis, QueryClause.GROUP_BY);
      List<ResolvedStructureKey> keys = new ArrayList<>(groups.size());
      boolean nullable = true;
      for (ResolvedExpression group : groups) {
        if (group.hasLocalColumns(analysis.path())) {
          keys.add(group.structureKey());
          nullable &= group.effectiveNullability().isNullable();
        }
      }
      if (keys.isEmpty() || !statement.distinct()) {
        return new ResultIdentity(keys, nullable, null);
      }
    }
    if (statement.distinct()) {
      Map<ResolvedStructureKey, ResolvedStructureKey> equivalents =
          equivalentSelectedOutputs(statement, analysis);
      List<ResolvedStructureKey> keys = new ArrayList<>(statement.selections().size());
      boolean nullable = true;
      for (ResolvedExpression output : expressions(analysis, QueryClause.SELECT)) {
        if (output.position().itemOrdinal() < statement.selections().size()) {
          keys.add(equivalents.getOrDefault(output.structureKey(), output.structureKey()));
          nullable &= output.effectiveNullability().isNullable();
        }
      }
      Set<ResolvedStructureKey> visible = new HashSet<>(keys);
      for (ResolvedExpression output : analysis.expressions()) {
        if (output.position().clause() == QueryClause.SELECT
            && output.position().itemOrdinal() >= statement.selections().size()
            && !visible.contains(
                equivalents.getOrDefault(output.structureKey(), output.structureKey()))) {
          return unknown("DISTINCT hidden selection changes the visible result identity");
        }
      }
      return new ResultIdentity(keys, nullable, null, List.of(), List.of(), equivalents);
    }
    List<ResolvedStructureKey> keys = new ArrayList<>();
    boolean nullable = true;
    List<ResolvedStructureKey> presence = List.of();
    List<List<ResolvedStructureKey>> requirements = List.of();
    Map<ResolvedStructureKey, ResolvedStructureKey> equivalentOutputs = Map.of();
    QueryBlockAnalysis[] children = null;
    for (int index = 0; index < analysis.sourceOccurrences().size(); index++) {
      QueryBlockAnalysis.SourceOccurrence occurrence = analysis.sourceOccurrences().get(index);
      if (occurrence.source() instanceof DerivedRelationSource && children == null) {
        children = new QueryBlockAnalysis[analysis.sourceOccurrences().size()];
        for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
          if (nested.kind() == QueryBlockAnalysis.NestedQueryKind.DERIVED_TABLE) {
            children[nested.location().itemOrdinal()] = nested.analysis();
          }
        }
      }
      ResultIdentity source = sourceIdentity(occurrence, children == null ? null : children[index]);
      if (source.failure != null) {
        return source;
      }
      if (!source.equivalentOutputs.isEmpty()) {
        if (equivalentOutputs.isEmpty()) {
          equivalentOutputs = new HashMap<>();
        }
        equivalentOutputs.putAll(source.equivalentOutputs);
      }
      if (!source.existenceRequirements.isEmpty()) {
        if (requirements.isEmpty()) {
          requirements = new ArrayList<>();
        }
        requirements.addAll(source.existenceRequirements);
      }
      if (index > 0) {
        JoinType type = statement.joins().get(index - 1).type();
        boolean rightNullable = source.nullable;
        if (type == JoinType.FULL && nullable && rightNullable) {
          if (presence.isEmpty() && source.presence.isEmpty()) {
            return unknown("FULL JOIN identities have no non-null existence component");
          }
          // Either side can distinguish the two unmatched all-NULL identities. Defer the
          // choice until ordering/export validation instead of fixing a preferred output.
          if (requirements.isEmpty()) {
            requirements = new ArrayList<>();
          }
          requirements.add(concat(presence, source.presence));
        }
        nullable =
            switch (type) {
              case INNER, CROSS -> nullable && rightNullable;
              case LEFT -> nullable;
              case RIGHT -> rightNullable;
              case FULL -> nullable || rightNullable;
            };
        presence =
            switch (type) {
              case INNER, CROSS -> concat(presence, source.presence);
              case LEFT -> presence;
              case RIGHT -> source.presence;
              case FULL -> List.of();
            };
      } else {
        nullable = source.nullable;
        presence = source.presence;
      }
      keys.addAll(source.components);
    }
    return new ResultIdentity(keys, nullable, null, presence, requirements, equivalentOutputs);
  }

  /** Whether the final result contains at most one row, including empty grouped input. */
  boolean atMostOneRow() {
    return failure == null && components.isEmpty() && existenceRequirements.isEmpty();
  }

  /**
   * Requires explicit ordering covering the complete final identity, except for singleton results.
   */
  void requireStableOrdering(SelectStatement statement, QueryBlockAnalysis analysis) {
    if (atMostOneRow()) {
      return;
    }
    if (statement.orderBy().isEmpty()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_ORDER_REQUIRED,
          "pagination requires explicit stable ORDER BY");
    }
    if (failure != null) {
      throw QueryValidationException.of(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER, failure);
    }
    Set<ResolvedStructureKey> ordered = new HashSet<>();
    for (ResolvedExpression expression : analysis.expressions()) {
      if (expression.position().clause() == QueryClause.ORDER_BY) {
        ordered.add(canonicalOutput(expression.structureKey()));
      }
    }
    for (ResolvedStructureKey component : components) {
      if (!ordered.contains(component)
          && !(statement.distinct() && matchesSelectedOrdering(statement, analysis, component))) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
            "pagination ORDER BY is missing final identity component " + component.canonicalForm());
      }
    }
    for (List<ResolvedStructureKey> alternatives : existenceRequirements) {
      if (alternatives.stream().noneMatch(ordered::contains)) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
            "FULL JOIN pagination ORDER BY is missing a non-null existence component");
      }
    }
  }

  private static boolean matchesSelectedOrdering(
      SelectStatement statement, QueryBlockAnalysis analysis, ResolvedStructureKey component) {
    for (ResolvedExpression output : analysis.expressions()) {
      int ordinal = output.position().itemOrdinal();
      if (output.position().clause() != QueryClause.SELECT
          || ordinal >= statement.selections().size()
          || !output.structureKey().equals(component)) {
        continue;
      }
      SqlExpression<?> selected = statement.selections().get(ordinal);
      for (ResolvedExpression expression : analysis.expressions()) {
        if (expression.position().clause() == QueryClause.ORDER_BY
            && selected.equals(
                statement.orderBy().get(expression.position().itemOrdinal()).expression())
            && output.columnDependencies().equals(expression.columnDependencies())) {
          // Both occurrences have already been resolved. Equal ASTs retain absolute
          // parameter slots; equal free dependencies retain their actual outer sources.
          return true;
        }
      }
    }
    return false;
  }

  private static Map<ResolvedStructureKey, ResolvedStructureKey> equivalentSelectedOutputs(
      SelectStatement statement, QueryBlockAnalysis analysis) {
    Map<ResolvedStructureKey, ResolvedStructureKey> equivalents = equivalentSourceOutputs(analysis);
    int size = statement.selections().size();
    if (size < 2) {
      return equivalents;
    }
    boolean[] nestedOutputs = null;
    int nestedCount = 0;
    for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
      QueryBlockLocation location = nested.location();
      int ordinal = location.itemOrdinal();
      if (location.clause() == QueryClause.SELECT && ordinal < size) {
        if (nestedOutputs == null) {
          nestedOutputs = new boolean[size];
        }
        if (!nestedOutputs[ordinal]) {
          nestedOutputs[ordinal] = true;
          nestedCount++;
        }
      }
    }
    if (nestedOutputs == null || nestedCount < 2) {
      return equivalents;
    }
    Map<SelectedExpressionIdentity, ResolvedStructureKey> seen = new HashMap<>();
    for (ResolvedExpression output : analysis.expressions()) {
      int ordinal = output.position().itemOrdinal();
      if (output.position().clause() != QueryClause.SELECT
          || ordinal >= size
          || !nestedOutputs[ordinal]) {
        continue;
      }
      // Occurrence paths differ between SELECT items. Use the already validated AST and free
      // dependencies, retaining absolute parameter slots and their original source identities.
      var identity =
          new SelectedExpressionIdentity(
              statement.selections().get(ordinal), output.columnDependencies());
      ResolvedStructureKey previous = seen.putIfAbsent(identity, output.structureKey());
      if (previous != null) {
        if (equivalents.isEmpty()) {
          equivalents = new HashMap<>();
        }
        equivalents.put(output.structureKey(), previous);
      }
    }
    return equivalents;
  }

  private static Map<ResolvedStructureKey, ResolvedStructureKey> equivalentSourceOutputs(
      QueryBlockAnalysis analysis) {
    Map<ResolvedStructureKey, ResolvedStructureKey> equivalents = Map.of();
    for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
      if (nested.kind() != QueryBlockAnalysis.NestedQueryKind.DERIVED_TABLE
          || nested.statement().selections().size() < 2) {
        continue;
      }
      QueryBlockAnalysis.SourceOccurrence source =
          analysis.sourceOccurrences().get(nested.location().itemOrdinal());
      DerivedRelationReference reference = ((DerivedRelationSource) source.source()).reference();
      Map<ResolvedStructureKey, ResolvedStructureKey> inner =
          equivalentSelectedOutputs(nested.statement(), nested.analysis());
      Map<ResolvedStructureKey, Integer> exports = new HashMap<>();
      for (ResolvedExpression output : nested.analysis().expressions()) {
        int ordinal = output.position().itemOrdinal();
        if (output.position().clause() != QueryClause.SELECT
            || ordinal >= nested.statement().selections().size()) {
          continue;
        }
        ResolvedStructureKey key = inner.getOrDefault(output.structureKey(), output.structureKey());
        Integer previous = exports.putIfAbsent(key, ordinal);
        if (previous != null) {
          if (equivalents.isEmpty()) {
            equivalents = new HashMap<>();
          }
          // Raw output equality survives DISTINCT and does not require the source's whole
          // row identity to be exported. Source occurrence remains part of each outer key.
          equivalents.put(
              derivedKey(source.identity(), reference, ordinal),
              derivedKey(source.identity(), reference, previous));
        }
      }
    }
    return equivalents;
  }

  static List<Integer> distinctOutputRepresentatives(
      SelectStatement statement, QueryBlockAnalysis analysis) {
    Map<ResolvedStructureKey, ResolvedStructureKey> equivalents =
        equivalentSelectedOutputs(statement, analysis);
    Map<ResolvedStructureKey, Integer> seen = new HashMap<>();
    List<Integer> representatives = new ArrayList<>(statement.selections().size());
    for (ResolvedExpression output : analysis.expressions()) {
      int ordinal = output.position().itemOrdinal();
      if (output.position().clause() == QueryClause.SELECT
          && ordinal < statement.selections().size()) {
        ResolvedStructureKey key =
            equivalents.getOrDefault(output.structureKey(), output.structureKey());
        Integer previous = seen.putIfAbsent(key, ordinal);
        representatives.add(previous == null ? ordinal : previous);
      }
    }
    return List.copyOf(representatives);
  }

  private record SelectedExpressionIdentity(
      SqlExpression<?> expression, List<ResolvedColumnReference> columnDependencies) {}

  private static ResultIdentity sourceIdentity(
      QueryBlockAnalysis.SourceOccurrence occurrence, @Nullable QueryBlockAnalysis child) {
    if (occurrence.source() instanceof ParameterRelationSource) {
      return new ResultIdentity(List.of(), true, null);
    }
    if (occurrence.source() instanceof EntityRelationSource(TableExpression<?> table)) {
      var primaryKey = table.entity().primaryKey();
      if (primaryKey.isEmpty()) {
        return unknown(
            "source occurrence #"
                + occurrence.identity().occurrenceOrdinal()
                + " has no primary-key identity");
      }
      List<ResolvedStructureKey> keys = new ArrayList<>();
      boolean nullable = true;
      for (PropertyMeta<?, ?> property : primaryKey.orElseThrow().properties()) {
        keys.add(columnKey(occurrence.identity(), property));
        nullable &= property.column().nullable();
      }
      return new ResultIdentity(keys, nullable, null);
    }
    DerivedRelationSource derived = (DerivedRelationSource) occurrence.source();
    QueryBlockAnalysis requiredChild =
        java.util.Objects.requireNonNull(child, "derived query analysis");
    ResultIdentity inner = resolve(derived.statement(), requiredChild);
    if (inner.failure != null) {
      return inner;
    }
    List<ResolvedExpression> outputs = expressions(requiredChild, QueryClause.SELECT);
    var exports = new HashMap<ResolvedStructureKey, ResolvedExpression>();
    Map<ResolvedStructureKey, ResolvedStructureKey> equivalentOutputs = Map.of();
    List<ResolvedStructureKey> presence = List.of();
    for (ResolvedExpression output : outputs) {
      if (output.position().itemOrdinal() < derived.statement().selections().size()) {
        ResolvedExpression previous =
            exports.putIfAbsent(inner.canonicalOutput(output.structureKey()), output);
        if (previous != null) {
          if (equivalentOutputs.isEmpty()) {
            equivalentOutputs = new HashMap<>();
          }
          // Each alias maps directly to its representative, including across derived layers.
          equivalentOutputs.put(
              derivedKey(
                  occurrence.identity(), derived.reference(), output.position().itemOrdinal()),
              derivedKey(
                  occurrence.identity(), derived.reference(), previous.position().itemOrdinal()));
        } else if (inner.nullable && !output.effectiveNullability().isNullable()) {
          if (presence.isEmpty()) {
            presence = new ArrayList<>();
          }
          presence.add(
              derivedKey(
                  occurrence.identity(), derived.reference(), output.position().itemOrdinal()));
        }
      }
    }
    List<ResolvedStructureKey> keys = new ArrayList<>(inner.components.size());
    for (ResolvedStructureKey component : inner.components) {
      ResolvedExpression exported = exports.get(component);
      if (exported == null) {
        return unknown(
            "derived source occurrence #"
                + occurrence.identity().occurrenceOrdinal()
                + " does not export its complete original identity");
      }
      int ordinal = exported.position().itemOrdinal();
      keys.add(derivedKey(occurrence.identity(), derived.reference(), ordinal));
    }
    List<List<ResolvedStructureKey>> requirements =
        inner.existenceRequirements.isEmpty() ? List.of() : new ArrayList<>();
    for (List<ResolvedStructureKey> alternatives : inner.existenceRequirements) {
      List<ResolvedStructureKey> exportedAlternatives = new ArrayList<>();
      for (ResolvedStructureKey candidate : alternatives) {
        ResolvedExpression exported = exports.get(inner.canonicalOutput(candidate));
        if (exported != null) {
          exportedAlternatives.add(
              derivedKey(
                  occurrence.identity(), derived.reference(), exported.position().itemOrdinal()));
        }
      }
      if (exportedAlternatives.isEmpty()) {
        return unknown(
            "derived source occurrence #"
                + occurrence.identity().occurrenceOrdinal()
                + " does not export a required existence component");
      }
      requirements.add(List.copyOf(exportedAlternatives));
    }
    // Raw export preserves the tuple's non-all-null proof even if every individual output is
    // nullable, as with the two non-null primary keys of a FULL JOIN.
    return new ResultIdentity(
        keys, inner.nullable, null, presence, requirements, equivalentOutputs);
  }

  private ResolvedStructureKey canonicalOutput(ResolvedStructureKey key) {
    return equivalentOutputs.getOrDefault(key, key);
  }

  private static List<ResolvedStructureKey> concat(
      List<ResolvedStructureKey> left, List<ResolvedStructureKey> right) {
    if (left.isEmpty()) {
      return right;
    }
    if (right.isEmpty()) {
      return left;
    }
    List<ResolvedStructureKey> result = new ArrayList<>(left.size() + right.size());
    result.addAll(left);
    result.addAll(right);
    return List.copyOf(result);
  }

  private static ResolvedStructureKey columnKey(
      ResolvedSourceIdentity source, PropertyMeta<?, ?> property) {
    return new ResolvedStructureKey.Atom(
        "COLUMN",
        List.of(
            property.javaType().getName(), SqlType.fromJavaType(property.javaType()).name(),
            Nullability.of(property.column().nullable()).name(), source.blockPath().toString(),
            Integer.toString(source.occurrenceOrdinal()), Integer.toString(property.ordinal()),
            property.name(), property.column().name()));
  }

  private static ResolvedStructureKey derivedKey(
      ResolvedSourceIdentity source, DerivedRelationReference reference, int ordinal) {
    DerivedOutputColumn output = reference.outputs().get(ordinal);
    return new ResolvedStructureKey.Atom(
        "DERIVED_COLUMN",
        List.of(
            output.javaType().getName(),
            output.sqlType().name(),
            output.nullability().name(),
            source.blockPath().toString(),
            Integer.toString(source.occurrenceOrdinal()),
            Integer.toString(ordinal),
            output.name().value()));
  }

  private static List<ResolvedExpression> expressions(
      QueryBlockAnalysis analysis, QueryClause clause) {
    return analysis.expressions().stream()
        .filter(expression -> expression.position().clause() == clause)
        .toList();
  }

  private static ResultIdentity unknown(String reason) {
    return new ResultIdentity(List.of(), true, reason);
  }
}
