package io.skis.dialect.spi;

import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.dialect.internal.DialectQueryFeatures;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.QueryBlockPath;
import io.skis.sql.ast.QueryClause;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ResolvedExpression;
import io.skis.sql.ast.ResolvedSourceIdentity;
import io.skis.sql.ast.ResolvedStructureKey;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable controlled stage object for a statement whose applicable semantic analysis and
 * provenance have completed.
 *
 * <p>Construction is only available through {@link #analyze(StatementAst)}. The complete analysis
 * tree stays internal; dialects read only the framework-issued {@link Occurrence} values exposed by
 * this type. Query blocks without a complete query analysis (single-table mutations) are still
 * represented, but never receive a fabricated {@link QueryBlockAnalysis}.
 */
public final class ResolvedStatement {

  private final Object owner = new Object();
  private final StatementAst statement;
  private final @Nullable QueryBlockAnalysis analysis;
  private final List<Occurrence> occurrences;
  private final List<SourceOccurrence> sourceOccurrences;
  private final @Nullable ResolvedStructureKey structureKey;

  private ResolvedStatement(StatementAst statement, @Nullable QueryBlockAnalysis analysis) {
    this.statement = Objects.requireNonNull(statement, "statement");
    this.analysis = analysis;
    this.structureKey = analysis == null ? null : analysis.structureKey();
    OccurrenceCollector collector = new OccurrenceCollector(owner);
    if (analysis != null) {
      collector.collect(statement, analysis);
    }
    this.occurrences = collector.occurrences();
    this.sourceOccurrences = collector.sources();
  }

  /**
   * Analyzes a complete statement and returns the controlled resolved stage.
   *
   * <p>SELECT and COUNT statements receive a complete query-block analysis. The current portable
   * mutation statements receive their applicable complete semantic validation without inventing a
   * query analysis. Unsupported or incomplete nodes fail before any stage object exists.
   */
  public static ResolvedStatement analyze(StatementAst statement) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    Objects.requireNonNull(statement, "statement");
    return switch (statement) {
      case SelectStatement select ->
          new ResolvedStatement(select, SemanticValidator.analyzeComplete(select));
      case CountAst count -> new ResolvedStatement(count, SemanticValidator.analyzeComplete(count));
      case InsertStatement insert -> {
        SemanticValidator.validateComplete(insert);
        yield new ResolvedStatement(insert, null);
      }
      case UpdateStatement update -> {
        SemanticValidator.validateComplete(update);
        yield new ResolvedStatement(update, null);
      }
      case DeleteStatement delete -> {
        SemanticValidator.validateComplete(delete);
        yield new ResolvedStatement(delete, null);
      }
      default ->
          throw new IllegalArgumentException(
              "controlled statement analysis does not support node "
                  + statement.getClass().getName());
    };
  }

  /** Creates a stage for a statement whose query analysis was produced by the same pipeline. */
  static ResolvedStatement adopt(StatementAst statement, @Nullable QueryBlockAnalysis analysis) {
    return new ResolvedStatement(statement, analysis);
  }

  /** The immutable statement that this stage resolved. */
  public StatementAst statement() {
    return statement;
  }

  /** Whether this stage owns a complete query-block analysis. */
  public boolean hasQueryBlockAnalysis() {
    return analysis != null;
  }

  /** Root resolved structure key for a query stage; empty for mutation-only statements. */
  public Optional<ResolvedStructureKey> structureKey() {
    return Optional.ofNullable(structureKey);
  }

  /** All expression occurrences in deterministic query-block and clause order. */
  public List<Occurrence> occurrences() {
    return occurrences;
  }

  /** All relation occurrences, including nested query blocks, in deterministic order. */
  public List<SourceOccurrence> sourceOccurrences() {
    return sourceOccurrences;
  }

  /** Returns the occurrence at an exact structural position or fails when it is not resolved. */
  public Occurrence occurrenceAt(QueryBlockPath blockPath, QueryClause clause, int itemOrdinal) {
    Objects.requireNonNull(blockPath, "blockPath");
    Objects.requireNonNull(clause, "clause");
    if (itemOrdinal < 0) {
      throw new IllegalArgumentException("itemOrdinal must not be negative");
    }
    for (Occurrence occurrence : occurrences) {
      if (occurrence.blockPath().equals(blockPath)
          && occurrence.clause() == clause
          && occurrence.itemOrdinal() == itemOrdinal) {
        return occurrence;
      }
    }
    throw new IllegalArgumentException(
        "statement has no resolved "
            + clause.displayName()
            + " item #"
            + itemOrdinal
            + " at query block "
            + blockPath);
  }

  /**
   * Returns restricted resolved information for an occurrence issued by this exact stage.
   *
   * <p>Passing a handle produced by another statement is rejected before the information is used.
   */
  public ExpressionInfo expressionInfo(Occurrence occurrence) {
    Occurrence required = Objects.requireNonNull(occurrence, "occurrence");
    if (required.owner() != owner) {
      throw new IllegalArgumentException(
          "expression occurrence does not belong to this resolved statement");
    }
    return required.info();
  }

  /**
   * Runs recursive dialect capability validation against this exact stage.
   *
   * <p>Dialects call this from {@link Dialect#validate(ResolvedStatement)}; the complete analysis
   * itself never leaves the dialect-api module.
   */
  void validateCapabilities(String dialectId, DialectCapabilities capabilities) {
    if (analysis == null) {
      DialectQueryFeatures.validate(dialectId, capabilities, statement);
      return;
    }
    DialectQueryFeatures.validate(dialectId, capabilities, statement, analysis);
  }

  /** Relation occurrence exposed as a restricted read-only source view. */
  public record SourceOccurrence(
      ResolvedSourceIdentity identity, RelationSource source, boolean nullExtended) {

    public SourceOccurrence {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(source, "source");
    }
  }

  private static final class OccurrenceCollector {

    private final Object owner;
    private final List<Occurrence> occurrences = new ArrayList<>();
    private final List<SourceOccurrence> sources = new ArrayList<>();

    private OccurrenceCollector(Object owner) {
      this.owner = owner;
    }

    private void collect(StatementAst statement, QueryBlockAnalysis analysis) {
      for (QueryBlockAnalysis.SourceOccurrence occurrence : analysis.sourceOccurrences()) {
        sources.add(
            new SourceOccurrence(
                occurrence.identity(), occurrence.source(), occurrence.nullExtended()));
      }
      for (ResolvedExpression resolved : analysis.expressions()) {
        if (statement instanceof CountAst count
            && resolved.position().clause() == QueryClause.SELECT
            && count.distinctExpression().isEmpty()) {
          // COUNT(*) analysis carries an internal TRUE placeholder, not a user-visible expression.
          continue;
        }
        SqlExpression<?> expression =
            expressionAt(
                statement, resolved.position().clause(), resolved.position().itemOrdinal());
        boolean correlated =
            resolved.columnDependencies().stream()
                .anyMatch(
                    dependency ->
                        !dependency.source().blockPath().equals(resolved.position().blockPath()));
        ExpressionInfo info =
            new ExpressionInfo(
                expression.javaType(),
                expression.sqlType(),
                resolved.effectiveNullability(),
                resolved.columnDependencies(),
                resolved.parameterDependencies(),
                correlated);
        occurrences.add(
            new Occurrence(
                owner,
                resolved.position().blockPath(),
                resolved.position().clause(),
                resolved.position().itemOrdinal(),
                expression,
                info));
      }
      for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
        collect(nested.statement(), nested.analysis());
      }
    }

    private static SqlExpression<?> expressionAt(
        StatementAst statement, QueryClause clause, int itemOrdinal) {
      if (statement instanceof SelectStatement select) {
        return selectExpression(select, clause, itemOrdinal);
      }
      if (statement instanceof CountAst count) {
        return switch (clause) {
          case JOIN_ON -> {
            int joinIndex = itemOrdinal - 1;
            if (joinIndex < 0 || joinIndex >= count.joins().size()) {
              throw new IllegalStateException("COUNT analysis has no JOIN ON #" + itemOrdinal);
            }
            yield count
                .joins()
                .get(joinIndex)
                .on()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "COUNT analysis has JOIN ON #" + itemOrdinal + " without a predicate"));
          }
          case SELECT ->
              count
                  .distinctExpression()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "COUNT analysis has a SELECT expression without a distinct "
                                  + "expression"));
          case WHERE ->
              count
                  .predicate()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "COUNT analysis has a WHERE expression without a predicate"));
          default ->
              throw new IllegalStateException(
                  "COUNT analysis has an unexpected " + clause.displayName() + " expression");
        };
      }
      throw new IllegalStateException(
          "query analysis belongs to unsupported statement " + statement.getClass().getName());
    }

    private static SqlExpression<?> selectExpression(
        SelectStatement statement, QueryClause clause, int itemOrdinal) {
      return switch (clause) {
        case SELECT -> {
          if (itemOrdinal < statement.selections().size()) {
            yield statement.selections().get(itemOrdinal);
          }
          int hidden = itemOrdinal - statement.selections().size();
          if (hidden < statement.hiddenSelections().size()) {
            yield statement.hiddenSelections().get(hidden).expression();
          }
          throw new IllegalStateException("SELECT analysis has no item #" + itemOrdinal);
        }
        case JOIN_ON -> {
          int joinIndex = itemOrdinal - 1;
          if (joinIndex < 0 || joinIndex >= statement.joins().size()) {
            throw new IllegalStateException("SELECT analysis has no JOIN ON #" + itemOrdinal);
          }
          yield statement
              .joins()
              .get(joinIndex)
              .on()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "SELECT analysis has JOIN ON #" + itemOrdinal + " without a predicate"));
        }
        case WHERE ->
            statement
                .where()
                .orElseThrow(
                    () ->
                        new IllegalStateException("SELECT analysis has WHERE without a predicate"));
        case GROUP_BY -> {
          if (itemOrdinal >= statement.groupBy().size()) {
            throw new IllegalStateException("SELECT analysis has no GROUP BY item #" + itemOrdinal);
          }
          yield statement.groupBy().get(itemOrdinal);
        }
        case HAVING ->
            statement
                .having()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "SELECT analysis has HAVING without a predicate"));
        case ORDER_BY -> {
          if (itemOrdinal >= statement.orderBy().size()) {
            throw new IllegalStateException("SELECT analysis has no ORDER BY item #" + itemOrdinal);
          }
          OrderByItem item = statement.orderBy().get(itemOrdinal);
          yield item.expression();
        }
        case PAGINATION -> {
          var pagination =
              statement
                  .pagination()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "SELECT analysis has pagination without a pagination node"));
          int next = 0;
          if (pagination instanceof KeysetSeek keyset) {
            if (itemOrdinal == next) {
              yield keyset.predicate();
            }
            next++;
          }
          if (itemOrdinal == next) {
            yield pagination.limit();
          }
          next++;
          if (pagination instanceof OffsetLimit offset && itemOrdinal == next) {
            yield offset.offset();
          }
          throw new IllegalStateException("SELECT analysis has no pagination item #" + itemOrdinal);
        }
        case JOIN_SOURCE ->
            throw new IllegalStateException("JOIN source analysis must not own an expression");
      };
    }

    private List<Occurrence> occurrences() {
      return List.copyOf(occurrences);
    }

    private List<SourceOccurrence> sources() {
      return List.copyOf(sources);
    }
  }
}
