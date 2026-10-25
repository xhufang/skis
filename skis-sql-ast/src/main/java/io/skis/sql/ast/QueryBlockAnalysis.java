package io.skis.sql.ast;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, query-local result of resolving one SELECT-shaped block against its embedding scope.
 *
 * <p>The result never writes identities, effective nullability, or correlation targets back to the
 * reusable {@link SelectStatement} or {@link CountAst}. An independent count uses its counted
 * expression as the block's single visible analysis expression. A child analyzed at another
 * structural location therefore receives an independent result.
 */
public final class QueryBlockAnalysis {

  private static final int AGGREGATE_STRUCTURE = 1;
  private static final int GROUPED = 1 << 1;
  private static final int STANDARD_EXPRESSION_LOWERING = 1 << 2;
  private static final int REAL_SUM = 1 << 3;
  private static final int SMALL_INTEGER_AVERAGE = 1 << 4;
  private static final int DOUBLE_AGGREGATE = 1 << 5;
  private static final int AT_MOST_ONE_ROW = 1 << 6;

  private final QueryBlockPath path;
  private final List<SourceOccurrence> sourceOccurrences;
  private final List<ResolvedExpression> expressions;
  private final List<NestedBlock> nestedBlocks;
  private final ResolvedStructureKey structureKey;
  private final Map<ScopeSite, ScopeSnapshot> clauseScopes;
  private final int characteristics;

  QueryBlockAnalysis(
      QueryBlockPath path,
      List<SourceOccurrence> sourceOccurrences,
      List<ResolvedExpression> expressions,
      List<NestedBlock> nestedBlocks,
      ResolvedStructureKey structureKey,
      Map<ScopeSite, ScopeSnapshot> clauseScopes,
      boolean aggregateStructure,
      boolean grouped,
      boolean standardExpressionLowering,
      boolean realSum,
      boolean smallIntegerAverage,
      boolean doubleAggregate,
      boolean atMostOneRow) {
    this.path = Objects.requireNonNull(path, "path");
    this.sourceOccurrences = List.copyOf(sourceOccurrences);
    this.expressions = List.copyOf(expressions);
    this.nestedBlocks = List.copyOf(nestedBlocks);
    this.structureKey = Objects.requireNonNull(structureKey, "structureKey");
    this.clauseScopes = Map.copyOf(clauseScopes);
    this.characteristics =
        (aggregateStructure ? AGGREGATE_STRUCTURE : 0)
            | (grouped ? GROUPED : 0)
            | (standardExpressionLowering ? STANDARD_EXPRESSION_LOWERING : 0)
            | (realSum ? REAL_SUM : 0)
            | (smallIntegerAverage ? SMALL_INTEGER_AVERAGE : 0)
            | (doubleAggregate ? DOUBLE_AGGREGATE : 0)
            | (atMostOneRow ? AT_MOST_ONE_ROW : 0);
  }

  /** Whether this block or a nested block contains explicit aggregate/grouping structure. */
  public boolean hasAggregateStructure() {
    return (characteristics & AGGREGATE_STRUCTURE) != 0;
  }

  /** Whether this block itself groups input rows; nested aggregates do not affect this fact. */
  public boolean isGrouped() {
    return (characteristics & GROUPED) != 0;
  }

  /**
   * Structural upper bound, independent of actual binding values and without folding arithmetic.
   */
  public boolean atMostOneRow() {
    return (characteristics & AT_MOST_ONE_ROW) != 0;
  }

  /** Validates stable offset ordering using this completed occurrence's result identity. */
  public void validateOffsetOrdering(SelectStatement statement) {
    ResultIdentity.resolve(statement, this).requireStableOrdering(statement, this);
  }

  /** Representative SELECT ordinal for each DISTINCT output under proven raw equivalences. */
  public List<Integer> distinctOutputRepresentatives(SelectStatement statement) {
    if (!statement.distinct()) {
      throw new IllegalArgumentException("output equivalence requires a DISTINCT SELECT");
    }
    return ResultIdentity.distinctOutputRepresentatives(statement, this);
  }

  /** Whether this block tree needs standard expression typing or grouping normalization. */
  public boolean requiresStandardExpressionLowering() {
    return (characteristics & STANDARD_EXPRESSION_LOWERING) != 0;
  }

  /** Whether this block tree contains SUM with a REAL input. */
  public boolean hasRealSum() {
    return (characteristics & REAL_SUM) != 0;
  }

  /** Whether this block tree contains AVG with a TINYINT, SMALLINT or INTEGER input. */
  public boolean hasSmallIntegerAverage() {
    return (characteristics & SMALL_INTEGER_AVERAGE) != 0;
  }

  /** Whether this block tree contains SUM or AVG with a DOUBLE input. */
  public boolean hasDoubleAggregate() {
    return (characteristics & DOUBLE_AGGREGATE) != 0;
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

  /** Nested SELECT occurrences in deterministic expression traversal order. */
  public List<NestedBlock> nestedBlocks() {
    return nestedBlocks;
  }

  /** Whether this block, including its nested expressions, depends on an ancestor source. */
  public boolean correlated() {
    return expressions.stream()
        .flatMap(expression -> expression.columnDependencies().stream())
        .anyMatch(dependency -> !dependency.source().blockPath().equals(path));
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

  /** One immutable nested SELECT occurrence and its context-specific analysis. */
  public record NestedBlock(
      SelectStatement statement,
      QueryBlockLocation location,
      NestedQueryKind kind,
      QueryBlockAnalysis analysis) {

    public NestedBlock {
      Objects.requireNonNull(statement, "statement");
      Objects.requireNonNull(location, "location");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(analysis, "analysis");
      List<QueryBlockLocation> locations = analysis.path().locations();
      if (locations.isEmpty() || !locations.getLast().equals(location)) {
        throw new IllegalArgumentException(
            "nested block analysis path does not match its embedding location");
      }
    }
  }

  /** SQL construct that owns one nested SELECT occurrence. */
  public enum NestedQueryKind {
    /** {@code EXISTS} or {@code NOT EXISTS}. */
    EXISTS,

    /** {@code IN (SELECT ...)} or {@code NOT IN (SELECT ...)}. */
    IN_SUBQUERY,

    /** A single-column SELECT evaluated as one nullable SQL value. */
    SCALAR_SUBQUERY,

    /** A non-correlated SELECT used as a FROM or JOIN relation source. */
    DERIVED_TABLE
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

    @Nullable DerivedRelationReference derivedReferenceOrNull() {
      return source.derivedReference().orElse(null);
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
