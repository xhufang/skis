package io.skis.dialect.internal;

import io.skis.core.SkisErrorCode;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.QueryClause;
import io.skis.sql.ast.ResolvedStructureKey;
import io.skis.sql.ast.ReturningStatement;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/** Recursive dialect capability validation for complete query-block trees. */
public final class DialectQueryFeatures {

  private DialectQueryFeatures() {}

  public static void validate(
      String dialectId, DialectCapabilities capabilities, StatementAst statement) {
    Objects.requireNonNull(dialectId, "dialectId");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(statement, "statement");
    switch (statement) {
      case ReturningStatement returning -> {
        require(dialectId, capabilities, DialectFeature.MUTATION_RETURNING, "RETURNING", "root");
        validate(dialectId, capabilities, returning.mutation());
      }
      case SelectStatement select ->
          validateBlock(dialectId, capabilities, select, SemanticValidator.analyzeComplete(select));
      case CountAst count ->
          validateCount(dialectId, capabilities, count, SemanticValidator.analyzeComplete(count));
      case InsertStatement insert -> {
        DialectJoinFeatures.validate(dialectId, capabilities, insert);
        if (insert.columns().isEmpty()) {
          require(
              dialectId,
              capabilities,
              DialectFeature.INSERT_DEFAULT_VALUES,
              "INSERT DEFAULT VALUES",
              "root");
        }
      }
      default -> DialectJoinFeatures.validate(dialectId, capabilities, statement);
    }
  }

  public static void validate(
      String dialectId,
      DialectCapabilities capabilities,
      StatementAst statement,
      QueryBlockAnalysis analysis) {
    Objects.requireNonNull(dialectId, "dialectId");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(analysis, "analysis");
    if (!analysis.path().locations().isEmpty()) {
      throw new IllegalArgumentException(
          "complete statement analysis must use the root query path");
    }
    switch (statement) {
      case SelectStatement select -> validateBlock(dialectId, capabilities, select, analysis);
      case CountAst count -> validateCount(dialectId, capabilities, count, analysis);
      default ->
          throw new IllegalArgumentException(
              "resolved query analysis cannot validate statement node "
                  + statement.getClass().getName());
    }
  }

  private static void validateCount(
      String dialectId,
      DialectCapabilities capabilities,
      CountAst statement,
      QueryBlockAnalysis analysis) {
    DialectJoinFeatures.validate(dialectId, capabilities, statement);
    require(
        dialectId,
        capabilities,
        DialectFeature.COUNT_AGGREGATE,
        "COUNT",
        analysis.path().toString());
    if (statement.distinctExpression().isPresent()) {
      require(
          dialectId,
          capabilities,
          DialectFeature.COUNT_DISTINCT,
          "COUNT DISTINCT",
          analysis.path().toString());
    }
    validateNested(dialectId, capabilities, analysis);
  }

  private static void validateBlock(
      String dialectId,
      DialectCapabilities capabilities,
      SelectStatement statement,
      QueryBlockAnalysis analysis) {
    if (analysis.path().locations().isEmpty()) {
      DialectJoinFeatures.validate(dialectId, capabilities, statement);
    } else {
      DialectJoinFeatures.validate(dialectId, capabilities, statement, analysis.path().toString());
    }
    if (!statement.groupBy().isEmpty()) {
      require(
          dialectId, capabilities, DialectFeature.GROUP_BY, "GROUP BY", analysis.path().toString());
      for (int index = 0; index < statement.groupBy().size(); index++) {
        if (statement.groupBy().get(index).sqlType() == SqlType.OTHER) {
          throw SqlRenderException.of(
              SkisErrorCode.DIALECT_INVALID_STRUCTURE,
              dialectId,
              "GROUP BY has no controlled equality type at query block "
                  + analysis.path()
                  + " GROUP BY item #"
                  + index);
        }
      }
    }
    if (statement.having().isPresent()) {
      require(dialectId, capabilities, DialectFeature.HAVING, "HAVING", analysis.path().toString());
    }
    if (analysis.hasAggregateStructure()) {
      for (var expression : analysis.expressions()) {
        validateAggregates(
            dialectId,
            capabilities,
            expression.structureKey(),
            analysis.path().toString(),
            expression.position().clause());
      }
    }
    validateNested(dialectId, capabilities, analysis);
  }

  private static void validateAggregates(
      String dialectId,
      DialectCapabilities capabilities,
      ResolvedStructureKey key,
      String blockPath,
      QueryClause clause) {
    if (!(key
            instanceof
            ResolvedStructureKey.Node(
                String kind1,
                java.util.List<String> attributes,
                java.util.List<ResolvedStructureKey> children))
        || kind1.equals("SELECT_BLOCK")) {
      return;
    }
    if (kind1.equals("AGGREGATE")) {
      String kind = attributes.get(3);
      DialectFeature feature =
          switch (kind) {
            case "COUNT" -> DialectFeature.COUNT_AGGREGATE;
            case "SUM" -> DialectFeature.SUM_AGGREGATE;
            case "AVG" -> DialectFeature.AVG_AGGREGATE;
            case "MIN" -> DialectFeature.MIN_AGGREGATE;
            case "MAX" -> DialectFeature.MAX_AGGREGATE;
            default -> throw new IllegalArgumentException("unknown aggregate kind");
          };
      require(dialectId, capabilities, feature, kind + " in " + clause.displayName(), blockPath);
      if (Boolean.parseBoolean(attributes.get(4))) {
        require(
            dialectId,
            capabilities,
            DialectFeature.COUNT_DISTINCT,
            "COUNT DISTINCT in " + clause.displayName(),
            blockPath);
      }
    }
    for (ResolvedStructureKey child : children) {
      validateAggregates(dialectId, capabilities, child, blockPath, clause);
    }
  }

  private static void validateNested(
      String dialectId, DialectCapabilities capabilities, QueryBlockAnalysis analysis) {
    for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
      switch (nested.kind()) {
        case EXISTS ->
            require(
                dialectId,
                capabilities,
                DialectFeature.EXISTS_SUBQUERY,
                "EXISTS subquery",
                nested.analysis().path().toString());
        case IN_SUBQUERY ->
            require(
                dialectId,
                capabilities,
                DialectFeature.IN_SUBQUERY,
                "IN subquery",
                nested.analysis().path().toString());
        case SCALAR_SUBQUERY ->
            require(
                dialectId,
                capabilities,
                DialectFeature.SCALAR_SUBQUERY,
                "scalar subquery",
                nested.analysis().path().toString());
        case DERIVED_TABLE ->
            require(
                dialectId,
                capabilities,
                DialectFeature.DERIVED_TABLE,
                "derived table",
                nested.analysis().path().toString());
      }
      if (nested.analysis().correlated()) {
        require(
            dialectId,
            capabilities,
            DialectFeature.CORRELATED_SUBQUERY,
            "correlated subquery",
            nested.analysis().path().toString());
      }
      validateBlock(dialectId, capabilities, nested.statement(), nested.analysis());
    }
  }

  private static void require(
      String dialectId,
      DialectCapabilities capabilities,
      DialectFeature feature,
      String description,
      String blockPath) {
    if (!capabilities.supports(feature)) {
      throw SqlRenderException.of(
          SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
          dialectId,
          feature.name(),
          "dialect '"
              + dialectId
              + "' does not support "
              + description
              + " at query block "
              + blockPath
              + " (missing "
              + feature
              + ")");
    }
  }
}
