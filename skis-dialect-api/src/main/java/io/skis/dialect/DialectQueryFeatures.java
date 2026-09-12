package io.skis.dialect;

import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/** Recursive dialect capability validation for complete query-block trees. */
final class DialectQueryFeatures {

  private DialectQueryFeatures() {}

  static void validate(String dialectId, DialectCapabilities capabilities, StatementAst statement) {
    Objects.requireNonNull(dialectId, "dialectId");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(statement, "statement");
    switch (statement) {
      case SelectStatement select ->
          validateBlock(dialectId, capabilities, select, SemanticValidator.analyzeComplete(select));
      case CountAst count -> {
        DialectJoinFeatures.validate(dialectId, capabilities, count);
        validateNested(dialectId, capabilities, SemanticValidator.analyzeComplete(count));
      }
      default -> DialectJoinFeatures.validate(dialectId, capabilities, statement);
    }
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
    validateNested(dialectId, capabilities, analysis);
  }

  private static void validateNested(
      String dialectId, DialectCapabilities capabilities, QueryBlockAnalysis analysis) {
    for (QueryBlockAnalysis.NestedBlock nested : analysis.nestedBlocks()) {
      require(
          dialectId,
          capabilities,
          DialectFeature.EXISTS_SUBQUERY,
          "EXISTS subquery",
          nested.analysis().path().toString());
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
      throw new SqlRenderException(
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
