package io.skis.dialect.spi;

import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.dialect.SqlRenderException;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.QueryClause;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectPagination;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, target-bound, value-independent rendering input produced at the end of the controlled
 * compilation pipeline.
 *
 * <p>Construction is only available through {@link #prepare(ResolvedStatement, Dialect)}, which
 * performs capability and custom validation, dialect lowering, portable normalization,
 * post-lowering validation, and final parameter layout in that order. The renderer may only
 * serialize {@link #statement()} and never reanalyzes, rewrites, or decides parameter positions.
 */
public final class RenderInput {

  private final StatementAst statement;
  private final ParameterLayout parameterLayout;
  private final Dialect target;

  private RenderInput(StatementAst statement, ParameterLayout parameterLayout, Dialect target) {
    this.statement = Objects.requireNonNull(statement, "statement");
    this.parameterLayout = Objects.requireNonNull(parameterLayout, "parameterLayout");
    this.target = Objects.requireNonNull(target, "target");
  }

  /**
   * Executes the complete target-bound compilation stage and returns renderer input.
   *
   * <p>The method never opens a connection and never reads a parameter value. Existing analysis is
   * reused only when lowering and portable normalization both returned the exact resolved
   * statement; any new structure receives a fresh complete analysis before layout.
   */
  public static RenderInput prepare(ResolvedStatement resolved, Dialect dialect) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    Objects.requireNonNull(resolved, "resolved");
    Objects.requireNonNull(dialect, "dialect");
    String dialectId = requireDialectId(dialect);

    // 1. Recursive capability/type validation plus the dialect's own pre-lowering validation.
    try {
      dialect.validate(resolved);
    } catch (IllegalArgumentException failure) {
      throw invalid(dialectId, failure, "dialect validation failed");
    }

    // 2-3. Dialect lowering followed by mandatory portable normalization. The normalizer is
    // idempotent for dialect lowering that already produced the final portable structure.
    StatementAst lowered;
    try {
      DialectLowering lowering = dialect.lowering();
      lowered = lowering.lower(resolved);
      validateDialectLoweringContract(resolved, lowered, dialectId, dialect.capabilities());
      lowered = PortableLowering.normalize(lowered, dialect.capabilities());
    } catch (IllegalArgumentException failure) {
      throw invalid(dialectId, failure, "dialect lowering produced an invalid structure");
    }

    // 4. Post-lowering validation. Identity reuses analysis; new structures are reanalyzed.
    ResolvedStatement finalResolved;
    if (lowered == resolved.statement()) {
      finalResolved = resolved;
    } else {
      finalResolved = analyzePostLowered(lowered, dialectId);
    }
    try {
      finalResolved.validateCapabilities(dialectId, dialect.capabilities());
      PostValidator.validate(finalResolved.statement(), dialect.capabilities(), dialectId);
      var originalAnalysis = resolved.queryAnalysis();
      var finalAnalysis = finalResolved.queryAnalysis();
      // Nested outputs are checked recursively by LoweringCorrespondence; portable empty-IN
      // rewrites retain their own block's aggregate, never an aggregate from a nested SELECT.
      if (originalAnalysis != null
          && finalAnalysis != null
          && originalAnalysis.isGrouped() != finalAnalysis.isGrouped()) {
        throw new IllegalArgumentException(
            "lowering changed the aggregate/grouping classification");
      }
      validateResultShape(resolved, finalResolved, dialectId);
    } catch (IllegalArgumentException failure) {
      throw invalid(dialectId, failure, "lowered statement failed post-validation");
    }

    // 5. Final logical slots and JDBC positions from the lowered structure.
    ParameterLayout layout = ParameterLayout.collect(finalResolved.statement());
    validateParameterIdentity(resolved.statement(), finalResolved.statement(), layout, dialectId);

    return new RenderInput(finalResolved.statement(), layout, dialect);
  }

  /** Final validated structure to serialize. */
  public StatementAst statement() {
    return statement;
  }

  /** Ordered final parameter positions that the SQL template must match exactly. */
  public ParameterLayout parameterLayout() {
    return parameterLayout;
  }

  /** Stable identifier of the dialect this input was prepared for. */
  public String dialectId() {
    return target.id();
  }

  /** Whether this input was prepared for the supplied dialect instance. */
  public boolean isPreparedFor(Dialect dialect) {
    return target == dialect;
  }

  /**
   * Rejects rendering when the supplied renderer target is not the exact dialect that prepared this
   * input. Comparing only the identifier is deliberately insufficient.
   */
  public void requireTarget(Dialect dialect) {
    Objects.requireNonNull(dialect, "dialect");
    if (!isPreparedFor(dialect)) {
      throw SqlRenderException.of(
          SkisErrorCode.DIALECT_INVALID_STRUCTURE,
          target.id(),
          "render input was prepared for dialect '"
              + target.id()
              + "' but rendered by dialect '"
              + dialect.id()
              + "'");
    }
  }

  private static ResolvedStatement analyzePostLowered(StatementAst statement, String dialectId) {
    try {
      return switch (statement) {
        case SelectStatement select ->
            ResolvedStatement.adopt(select, SemanticValidator.analyzeComplete(select));
        case CountAst count ->
            ResolvedStatement.adopt(count, SemanticValidator.analyzeComplete(count));
        case InsertStatement insert -> {
          SemanticValidator.validateComplete(insert);
          yield ResolvedStatement.adopt(insert, null);
        }
        case UpdateStatement update -> {
          SemanticValidator.validateComplete(update);
          yield ResolvedStatement.adopt(update, null);
        }
        case DeleteStatement delete -> {
          SemanticValidator.validateComplete(delete);
          yield ResolvedStatement.adopt(delete, null);
        }
        default ->
            throw new IllegalArgumentException(
                "post-lowering analysis does not support node " + statement.getClass().getName());
      };
    } catch (IllegalArgumentException failure) {
      throw invalid(dialectId, failure, "lowered statement is not semantically valid");
    }
  }

  private static void validateDialectLoweringContract(
      ResolvedStatement originalStage,
      StatementAst lowered,
      String dialectId,
      DialectCapabilities capabilities) {
    StatementAst original = originalStage.statement();
    if (original == lowered) {
      return;
    }
    if (original.getClass() != lowered.getClass()) {
      throw invalid(
          dialectId,
          new IllegalArgumentException(
              "lowering changed statement kind from "
                  + original.getClass().getSimpleName()
                  + " to "
                  + lowered.getClass().getSimpleName()),
          "dialect lowering changed the public statement contract");
    }
    if (original instanceof SelectStatement originalSelect
        && lowered instanceof SelectStatement loweredSelect) {
      if (originalSelect.distinct() != loweredSelect.distinct()) {
        throw changedResultShape(dialectId, "lowering changed SELECT DISTINCT semantics");
      }
    }
    var analysis = originalStage.queryAnalysis();
    LoweringCorrespondence.validate(
        original, lowered, analysis != null && analysis.hasAggregateStructure(), capabilities);
  }

  private static void validateResultShape(
      ResolvedStatement original, ResolvedStatement lowered, String dialectId) {
    if (original == lowered) {
      return;
    }
    if (!(original.statement() instanceof SelectStatement originalSelect)
        || !(lowered.statement() instanceof SelectStatement loweredSelect)) {
      return;
    }
    if (originalSelect.selections().size() != loweredSelect.selections().size()) {
      throw changedResultShape(dialectId, "lowering changed the visible SELECT item count");
    }
    if (originalSelect.hiddenSelections().size() != loweredSelect.hiddenSelections().size()) {
      throw changedResultShape(dialectId, "lowering changed the hidden SELECT item count");
    }
    int visibleCount = originalSelect.selections().size();
    int totalCount = visibleCount + originalSelect.hiddenSelections().size();
    Iterator<Occurrence> originalOccurrences = original.occurrences().iterator();
    Iterator<Occurrence> loweredOccurrences = lowered.occurrences().iterator();
    for (int index = 0; index < totalCount; index++) {
      ExpressionInfo originalInfo = original.expressionInfo(nextRootSelection(originalOccurrences));
      ExpressionInfo loweredInfo = lowered.expressionInfo(nextRootSelection(loweredOccurrences));
      if (!sameResultDescriptor(originalInfo, loweredInfo)) {
        String visibility = index < visibleCount ? "visible" : "hidden";
        int outputIndex = index < visibleCount ? index : index - visibleCount;
        throw changedResultShape(
            dialectId,
            "lowering changed the "
                + visibility
                + " SELECT item descriptor at index "
                + outputIndex);
      }
    }
    for (int index = 0; index < originalSelect.hiddenSelections().size(); index++) {
      if (!originalSelect
          .hiddenSelections()
          .get(index)
          .alias()
          .equals(loweredSelect.hiddenSelections().get(index).alias())) {
        throw changedResultShape(
            dialectId, "lowering changed the hidden SELECT alias at index " + index);
      }
    }
  }

  private static Occurrence nextRootSelection(Iterator<Occurrence> occurrences) {
    while (occurrences.hasNext()) {
      Occurrence occurrence = occurrences.next();
      if (!occurrence.nested() && occurrence.clause() == QueryClause.SELECT) {
        return occurrence;
      }
    }
    throw new IllegalArgumentException("resolved statement is missing a root SELECT occurrence");
  }

  private static boolean sameResultDescriptor(ExpressionInfo first, ExpressionInfo second) {
    return first.javaType().equals(second.javaType())
        && first.sqlType() == second.sqlType()
        && first.effectiveNullability() == second.effectiveNullability();
  }

  private static void validateParameterIdentity(
      StatementAst originalStatement,
      StatementAst finalStatement,
      ParameterLayout finalLayout,
      String dialectId) {
    if (originalStatement == finalStatement) {
      return;
    }
    ParameterLayout originalLayout = ParameterLayout.collect(originalStatement);
    Map<Integer, ParameterSlot<?>> originals = new HashMap<>();
    for (ParameterLayout.ParameterPosition position : originalLayout.positions()) {
      ParameterSlot<?> slot = position.slot();
      ParameterSlot<?> previous = originals.putIfAbsent(slot.ordinal(), slot);
      if (previous != null && notSameDescriptor(previous, slot)) {
        throw parameterShape(
            dialectId,
            "lowering changed the logical parameter descriptor at ordinal " + slot.ordinal());
      }
    }
    for (ParameterLayout.ParameterPosition position : finalLayout.positions()) {
      ParameterSlot<?> slot = position.slot();
      ParameterSlot<?> original = originals.get(slot.ordinal());
      if (original == null) {
        throw parameterShape(
            dialectId,
            "lowering introduced parameter ordinal "
                + slot.ordinal()
                + " without an original logical parameter");
      }
      if (notSameDescriptor(original, slot)) {
        throw parameterShape(
            dialectId,
            "lowering changed the logical parameter descriptor at ordinal " + slot.ordinal());
      }
    }
  }

  private static boolean notSameDescriptor(ParameterSlot<?> first, ParameterSlot<?> second) {
    return first.ordinal() != second.ordinal()
        || !first.javaType().equals(second.javaType())
        || first.sqlType() != second.sqlType()
        || first.nullability() != second.nullability();
  }

  private static String requireDialectId(Dialect dialect) {
    String id = dialect.id();
    if (id.isBlank()) {
      throw new IllegalArgumentException("dialect id must not be blank");
    }
    return id;
  }

  private static SqlRenderException invalid(
      String dialectId, IllegalArgumentException failure, String summary) {
    String detail = failure.getMessage();
    return SqlRenderException.of(
        SkisErrorCode.DIALECT_INVALID_STRUCTURE,
        dialectId,
        summary + (detail == null || detail.isBlank() ? "" : ": " + detail));
  }

  private static SqlRenderException parameterShape(String dialectId, String detail) {
    return SqlRenderException.of(SkisErrorCode.DIALECT_PARAMETER_SHAPE, dialectId, detail);
  }

  private static SqlRenderException changedResultShape(String dialectId, String detail) {
    return invalid(
        dialectId,
        new IllegalArgumentException(detail),
        "dialect lowering changed the public result shape");
  }

  private static final class PostValidator {

    private PostValidator() {}

    private static void validate(
        StatementAst statement, DialectCapabilities capabilities, String dialectId) {
      switch (statement) {
        case SelectStatement select -> validateSelect(select, capabilities, dialectId);
        case CountAst count -> validateCount(count, capabilities, dialectId);
        case InsertStatement insert -> {
          validateTable(insert.target(), capabilities, dialectId);
          if (insert.columns().isEmpty()
              && !capabilities.supports(DialectFeature.INSERT_DEFAULT_VALUES)) {
            throw SqlRenderException.of(
                SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
                dialectId,
                DialectFeature.INSERT_DEFAULT_VALUES.name(),
                "lowered INSERT requires DEFAULT VALUES support");
          }
          insert.values().forEach(value -> validateExpression(value, capabilities, dialectId));
        }
        case UpdateStatement update -> {
          validateTable(update.target(), capabilities, dialectId);
          if (update.target().alias().isPresent()) {
            throw invalid(
                dialectId,
                new IllegalArgumentException("mutation target tables must not carry an alias"),
                "lowered statement failed post-validation");
          }
          for (UpdateAssignment<?> assignment : update.assignments()) {
            validateExpression(assignment.value(), capabilities, dialectId);
          }
          validateExpression(update.where(), capabilities, dialectId);
        }
        case DeleteStatement delete -> {
          validateTable(delete.target(), capabilities, dialectId);
          if (delete.target().alias().isPresent()) {
            throw invalid(
                dialectId,
                new IllegalArgumentException("mutation target tables must not carry an alias"),
                "lowered statement failed post-validation");
          }
          validateExpression(delete.where(), capabilities, dialectId);
        }
        default ->
            throw invalid(
                dialectId,
                new IllegalArgumentException(
                    "unsupported statement node " + statement.getClass().getName()),
                "lowered statement failed post-validation");
      }
    }

    private static void validateSelect(
        SelectStatement select, DialectCapabilities capabilities, String dialectId) {
      validateFromClause(select.fromClause(), capabilities, dialectId);
      select.selections().forEach(value -> validateExpression(value, capabilities, dialectId));
      select
          .hiddenSelections()
          .forEach(item -> validateExpression(item.expression(), capabilities, dialectId));
      select.where().ifPresent(value -> validateExpression(value, capabilities, dialectId));
      select.groupBy().forEach(value -> validateExpression(value, capabilities, dialectId));
      select.having().ifPresent(value -> validateExpression(value, capabilities, dialectId));
      for (OrderByItem item : select.orderBy()) {
        if (item.expression() instanceof SelectedOutputReference<?> reference) {
          validateSelectedOutputReference(select, reference, dialectId);
        } else {
          validateExpression(item.expression(), capabilities, dialectId);
        }
        if (item.nullOrder() != NullOrder.DIALECT_DEFAULT
            && !capabilities.supports(DialectFeature.NULLS_FIRST_LAST)) {
          throw SqlRenderException.of(
              SkisErrorCode.DIALECT_NULLS_ORDERING_UNSUPPORTED,
              dialectId,
              "NULLS_FIRST_LAST",
              "dialect '"
                  + dialectId
                  + "' has no native NULLS FIRST/LAST and did not lower the ordering");
        }
      }
      select
          .pagination()
          .ifPresent(pagination -> validatePagination(pagination, capabilities, dialectId));
    }

    private static void validateCount(
        CountAst count, DialectCapabilities capabilities, String dialectId) {
      validateFromClause(count.fromClause(), capabilities, dialectId);
      count.predicate().ifPresent(value -> validateExpression(value, capabilities, dialectId));
      if (count.distinctExpression().isPresent()) {
        if (!capabilities.supports(DialectFeature.COUNT_DISTINCT)) {
          throw SqlRenderException.of(
              SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
              dialectId,
              DialectFeature.COUNT_DISTINCT.name(),
              "dialect '"
                  + dialectId
                  + "' does not support distinct count (missing "
                  + DialectFeature.COUNT_DISTINCT
                  + ")");
        }
        SqlExpression<?> expression = count.distinctExpression().orElseThrow();
        validateExpression(expression, capabilities, dialectId);
        boolean nullable = count.fromClause().effectiveNullability(expression).isNullable();
        if (nullable && !count.nullCompensated()) {
          throw invalid(
              dialectId,
              new IllegalArgumentException(
                  "nullable distinct count was not normalized with NULL compensation"),
              "lowered statement failed post-validation");
        }
      }
    }

    private static void validateSelectedOutputReference(
        SelectStatement select, SelectedOutputReference<?> reference, String dialectId) {
      if (!select.distinct()) {
        throw invalid(
            dialectId,
            new IllegalArgumentException("selected output references require a DISTINCT SELECT"),
            "lowered statement failed post-validation");
      }
      if (reference.position() < 1 || reference.position() > select.selections().size()) {
        throw invalid(
            dialectId,
            new IllegalArgumentException(
                "selected output position is outside the visible SELECT list"),
            "lowered statement failed post-validation");
      }
      SqlExpression<?> selected = select.selections().get(reference.position() - 1);
      if (!selected.javaType().equals(reference.javaType())
          || selected.sqlType() != reference.sqlType()
          || select.fromClause().effectiveNullability(selected) != reference.nullability()) {
        throw invalid(
            dialectId,
            new IllegalArgumentException(
                "selected output reference descriptor does not match its SELECT item"),
            "lowered statement failed post-validation");
      }
    }

    private static void validatePagination(
        SelectPagination pagination, DialectCapabilities capabilities, String dialectId) {
      if (!capabilities.supports(DialectFeature.PARAMETERIZED_LIMIT)) {
        throw SqlRenderException.of(
            SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
            dialectId,
            DialectFeature.PARAMETERIZED_LIMIT.name(),
            "dialect '"
                + dialectId
                + "' does not support parameterized LIMIT (missing "
                + DialectFeature.PARAMETERIZED_LIMIT
                + ")");
      }
      if (pagination instanceof OffsetLimit
          && !capabilities.supports(DialectFeature.PARAMETERIZED_OFFSET)) {
        throw SqlRenderException.of(
            SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
            dialectId,
            DialectFeature.PARAMETERIZED_OFFSET.name(),
            "dialect '"
                + dialectId
                + "' does not support parameterized OFFSET (missing "
                + DialectFeature.PARAMETERIZED_OFFSET
                + ")");
      }
      if (pagination instanceof KeysetSeek keyset) {
        validateExpression(keyset.predicate(), capabilities, dialectId);
      }
    }

    private static void validateFromClause(
        FromClause fromClause, DialectCapabilities capabilities, String dialectId) {
      validateRelation(fromClause.root(), capabilities, dialectId);
      for (JoinClause join : fromClause.joins()) {
        validateRelation(join.right(), capabilities, dialectId);
        join.on().ifPresent(value -> validateExpression(value, capabilities, dialectId));
      }
    }

    private static void validateRelation(
        RelationSource source, DialectCapabilities capabilities, String dialectId) {
      if (source instanceof DerivedRelationSource derived) {
        if (!capabilities.supports(DialectFeature.DERIVED_TABLE)) {
          throw SqlRenderException.of(
              SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
              dialectId,
              DialectFeature.DERIVED_TABLE.name(),
              "dialect '"
                  + dialectId
                  + "' does not support derived tables (missing "
                  + DialectFeature.DERIVED_TABLE
                  + ")");
        }
        validateSelect(derived.statement(), capabilities, dialectId);
      } else if (source instanceof ParameterRelationSource parameters) {
        parameters
            .parameters()
            .forEach(value -> validateExpression(value, capabilities, dialectId));
      } else {
        source.entityTable().ifPresent(table -> validateTable(table, capabilities, dialectId));
      }
    }

    private static void validateTable(
        TableExpression<?> table, DialectCapabilities capabilities, String dialectId) {
      var metadata = table.entity().table();
      if (metadata.hasCatalog()
          && !capabilities.supports(DialectFeature.CATALOG_QUALIFIED_TABLES)) {
        throw SqlRenderException.of(
            SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
            dialectId,
            DialectFeature.CATALOG_QUALIFIED_TABLES.name(),
            "dialect '"
                + dialectId
                + "' does not support catalog-qualified tables (missing "
                + DialectFeature.CATALOG_QUALIFIED_TABLES
                + ")");
      }
      if (metadata.hasSchema() && !capabilities.supports(DialectFeature.SCHEMA_QUALIFIED_TABLES)) {
        throw SqlRenderException.of(
            SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
            dialectId,
            DialectFeature.SCHEMA_QUALIFIED_TABLES.name(),
            "dialect '"
                + dialectId
                + "' does not support schema-qualified tables (missing "
                + DialectFeature.SCHEMA_QUALIFIED_TABLES
                + ")");
      }
    }

    private static void validateExpression(
        SqlExpression<?> expression, DialectCapabilities capabilities, String dialectId) {
      switch (expression) {
        case AggregateExpression<?> aggregate ->
            aggregate
                .operand()
                .ifPresent(value -> validateExpression(value, capabilities, dialectId));
        case ParameterSlot<?> ignored -> {}
        case BooleanPredicate ignored -> {}
        case SelectedOutputReference<?> ignored ->
            throw invalid(
                dialectId,
                new IllegalArgumentException(
                    "selected output references are only valid as direct DISTINCT ORDER BY items"),
                "lowered statement failed post-validation");
        case ColumnExpression<?, ?> ignored -> {}
        case DerivedColumnExpression<?> ignored -> {}
        case LiteralExpression<?> ignored -> {}
        case ArithmeticExpression<?> arithmetic -> {
          validateExpression(arithmetic.left(), capabilities, dialectId);
          validateExpression(arithmetic.right(), capabilities, dialectId);
        }
        case ConcatExpression concat ->
            concat.operands().forEach(value -> validateExpression(value, capabilities, dialectId));
        case CaseExpression<?> caseExpression -> {
          for (CaseWhen<?> branch : caseExpression.branches()) {
            validateExpression(branch.condition(), capabilities, dialectId);
            validateExpression(branch.result(), capabilities, dialectId);
          }
          caseExpression
              .otherwise()
              .ifPresent(value -> validateExpression(value, capabilities, dialectId));
        }
        case CastExpression<?> cast -> validateExpression(cast.operand(), capabilities, dialectId);
        case CoalesceExpression<?> coalesce ->
            coalesce
                .operands()
                .forEach(value -> validateExpression(value, capabilities, dialectId));
        case ComparisonPredicate<?> comparison -> {
          validateExpression(comparison.left(), capabilities, dialectId);
          validateExpression(comparison.right(), capabilities, dialectId);
        }
        case LogicalPredicate logical ->
            logical.operands().forEach(value -> validateExpression(value, capabilities, dialectId));
        case NullPredicate nullPredicate ->
            validateExpression(nullPredicate.operand(), capabilities, dialectId);
        case BetweenPredicate<?> between -> {
          validateExpression(between.value(), capabilities, dialectId);
          validateExpression(between.lower(), capabilities, dialectId);
          validateExpression(between.upper(), capabilities, dialectId);
        }
        case LikePredicate like -> {
          validateExpression(like.value(), capabilities, dialectId);
          validateExpression(like.pattern(), capabilities, dialectId);
        }
        case InPredicate<?> in -> {
          if (in.candidates().isEmpty()) {
            throw invalid(
                dialectId,
                new IllegalArgumentException(
                    "empty IN/NOT IN must be normalized to a deterministic predicate"),
                "lowered statement failed post-validation");
          }
          validateExpression(in.value(), capabilities, dialectId);
          in.candidates().forEach(value -> validateExpression(value, capabilities, dialectId));
        }
        case InSubqueryPredicate<?> in -> {
          validateExpression(in.value(), capabilities, dialectId);
          validateSelect(in.subquery(), capabilities, dialectId);
        }
        case ExistsPredicate exists -> validateSelect(exists.subquery(), capabilities, dialectId);
        case ScalarSubqueryExpression<?> scalar ->
            validateSelect(scalar.subquery(), capabilities, dialectId);
        case NotPredicate not -> validateExpression(not.operand(), capabilities, dialectId);
        case IncrementExpression<?> increment ->
            validateExpression(increment.operand(), capabilities, dialectId);
        default ->
            throw invalid(
                dialectId,
                new IllegalArgumentException(
                    "unsupported expression node " + expression.getClass().getName()),
                "lowered statement failed post-validation");
      }
    }
  }
}
