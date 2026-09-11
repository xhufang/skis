package io.skis.sql.ast;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, query-local result of resolving one SELECT block against its embedding scope.
 *
 * <p>The result never writes identities, effective nullability, or correlation targets back to the
 * reusable {@link SelectStatement}. A child analyzed at another structural location therefore
 * receives an independent result.
 */
public final class QueryBlockAnalysis {

  private final QueryBlockPath path;
  private final List<SourceOccurrence> sourceOccurrences;
  private final List<ResolvedExpression> expressions;
  private final ResolvedStructureKey structureKey;
  private final Map<ScopeSite, ScopeSnapshot> clauseScopes;

  QueryBlockAnalysis(
      QueryBlockPath path,
      List<SourceOccurrence> sourceOccurrences,
      List<ResolvedExpression> expressions,
      ResolvedStructureKey structureKey,
      Map<ScopeSite, ScopeSnapshot> clauseScopes) {
    this.path = Objects.requireNonNull(path, "path");
    this.sourceOccurrences = List.copyOf(sourceOccurrences);
    this.expressions = List.copyOf(expressions);
    this.structureKey = Objects.requireNonNull(structureKey, "structureKey");
    this.clauseScopes = Map.copyOf(clauseScopes);
  }

  /** Stable structural path of this block. */
  public QueryBlockPath path() {
    return path;
  }

  /** Ordered source occurrences after the block's complete Join chain. */
  public List<SourceOccurrence> sourceOccurrences() {
    return sourceOccurrences;
  }

  /** Resolved top-level expressions in deterministic clause order. */
  public List<ResolvedExpression> expressions() {
    return expressions;
  }

  /** Value- and object-identity-independent key for the resolved block. */
  public ResolvedStructureKey structureKey() {
    return structureKey;
  }

  /**
   * Resolves a child block using exactly the parent scope visible at its structural location.
   *
   * <p>{@link QueryClause#JOIN_SOURCE} is deliberately a non-correlated boundary; the child keeps
   * its structural path but receives no parent scope. LATERAL/APPLY requires a future explicit
   * source kind and must not be simulated through this method.
   */
  public QueryBlockAnalysis analyzeChild(SelectStatement child, QueryBlockLocation location) {
    Objects.requireNonNull(child, "child");
    Objects.requireNonNull(location, "location");
    ScopeSnapshot visibleScope =
        clauseScopes.get(new ScopeSite(location.clause(), location.itemOrdinal()));
    if (visibleScope == null) {
      throw new IllegalArgumentException(
          "query block "
              + path
              + " has no "
              + location.clause().displayName()
              + " item #"
              + location.itemOrdinal()
              + " for nested block #"
              + location.nestedOrdinal());
    }
    ScopeSnapshot parentScope =
        location.clause() == QueryClause.JOIN_SOURCE ? ScopeSnapshot.empty() : visibleScope;
    return QueryScopeAnalyzer.analyzeNested(child, path.child(location), parentScope);
  }

  /** Resolved final source occurrence. */
  public record SourceOccurrence(
      ResolvedSourceIdentity identity, RelationSource source, boolean nullExtended) {

    public SourceOccurrence {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(source, "source");
    }
  }

  record ScopeSite(QueryClause clause, int itemOrdinal) {

    ScopeSite {
      Objects.requireNonNull(clause, "clause");
      if (itemOrdinal < 0) {
        throw new IllegalArgumentException("itemOrdinal must not be negative");
      }
    }
  }

  record ScopeSource(ResolvedSourceIdentity identity, RelationSource source, boolean nullExtended) {

    ScopeSource {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(source, "source");
    }

    @Nullable TableExpression<?> entityTableOrNull() {
      return source.entityTable().orElse(null);
    }
  }

  record ScopeFrame(
      QueryBlockPath path, List<ScopeSource> sources, List<ScopeSource> allBlockSources) {

    ScopeFrame {
      Objects.requireNonNull(path, "path");
      sources = List.copyOf(sources);
      allBlockSources = List.copyOf(allBlockSources);
    }
  }

  record ScopeSnapshot(List<ScopeFrame> frames) {

    ScopeSnapshot {
      frames = List.copyOf(frames);
    }

    static ScopeSnapshot empty() {
      return new ScopeSnapshot(List.of());
    }

    ScopeSnapshot prepend(ScopeFrame frame) {
      java.util.ArrayList<ScopeFrame> result = new java.util.ArrayList<>(frames.size() + 1);
      result.add(Objects.requireNonNull(frame, "frame"));
      result.addAll(frames);
      return new ScopeSnapshot(result);
    }
  }
}
