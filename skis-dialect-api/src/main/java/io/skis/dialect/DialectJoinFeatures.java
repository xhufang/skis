package io.skis.dialect;

import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/** Single internal mapping between portable join kinds, dialect features, and SQL keywords. */
final class DialectJoinFeatures {

  private DialectJoinFeatures() {}

  static DialectFeature feature(JoinType type) {
    return switch (type) {
      case INNER -> DialectFeature.INNER_JOIN;
      case LEFT -> DialectFeature.LEFT_JOIN;
      case RIGHT -> DialectFeature.RIGHT_JOIN;
      case FULL -> DialectFeature.FULL_JOIN;
      case CROSS -> DialectFeature.CROSS_JOIN;
    };
  }

  static String keyword(JoinType type) {
    return switch (type) {
      case INNER -> "INNER JOIN";
      case LEFT -> "LEFT JOIN";
      case RIGHT -> "RIGHT JOIN";
      case FULL -> "FULL JOIN";
      case CROSS -> "CROSS JOIN";
    };
  }

  static void validate(
      String dialectId, DialectCapabilities capabilities, StatementAst statement) {
    Objects.requireNonNull(dialectId, "dialectId");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(statement, "statement");
    switch (statement) {
      case SelectStatement select ->
          validate(dialectId, capabilities, select.fromClause(), "SELECT");
      case CountAst count -> validate(dialectId, capabilities, count.fromClause(), "COUNT");
      default -> {
        // The current portable mutation AST has no Join-bearing source.
      }
    }
  }

  private static void validate(
      String dialectId,
      DialectCapabilities capabilities,
      FromClause fromClause,
      String statementKind) {
    for (int index = 0; index < fromClause.joins().size(); index++) {
      JoinClause join = fromClause.joins().get(index);
      if (!capabilities.supports(feature(join.type()))) {
        throw new SqlRenderException(
            "dialect '"
                + dialectId
                + "' does not support "
                + join.type()
                + " JOIN at "
                + statementKind
                + " FROM join #"
                + (index + 1));
      }
    }
  }
}
