package io.skis.sql.ast;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Explicit mutation outputs; the result-table form is selected by dialect lowering. */
public record ReturningStatement(
    StatementAst mutation,
    List<? extends SqlExpression<?>> selections,
    Form form,
    @Nullable Identifier resultAlias)
    implements StatementAst {

  public enum Form {
    NATIVE,
    FINAL_TABLE,
    OLD_TABLE
  }

  public ReturningStatement(StatementAst mutation, List<? extends SqlExpression<?>> selections) {
    this(mutation, selections, Form.NATIVE);
  }

  public ReturningStatement(
      StatementAst mutation, List<? extends SqlExpression<?>> selections, Form form) {
    this(mutation, selections, form, form == Form.NATIVE ? null : DERIVED_ALIAS);
  }

  /**
   * Private identity marker: the canonical constructor derives the result alias once instead of
   * deriving it here and re-deriving it for verification.
   */
  private static final Identifier DERIVED_ALIAS = Identifier.of("_skis_derived_alias");

  private static @Nullable Identifier resultAlias(List<? extends SqlExpression<?>> outputs) {
    Set<String> names = new HashSet<>();
    boolean[] nested = {false};
    SqlAstTraversal.visitExpressions(
        outputs,
        expression -> {
          if (expression instanceof ExistsPredicate
              || expression instanceof InSubqueryPredicate<?>
              || expression instanceof ScalarSubqueryExpression<?>) nested[0] = true;
        },
        source -> {
          switch (source) {
            case EntityRelationSource entity ->
                names.add(
                    entity
                        .table()
                        .alias()
                        .map(Identifier::value)
                        .orElse(entity.table().entity().table().name()));
            case DerivedRelationSource derived -> names.add(derived.reference().alias().value());
            case ParameterRelationSource parameters ->
                names.add(parameters.reference().alias().value());
            default -> throw new IllegalArgumentException("unknown RETURNING source");
          }
        });
    if (!nested[0]) return null;
    String alias = "_skis_written";
    for (int suffix = 1; names.contains(alias); suffix++) alias = "_skis_written_" + suffix;
    return Identifier.of(alias);
  }

  public ReturningStatement {
    Objects.requireNonNull(mutation, "mutation");
    selections = List.copyOf(selections);
    Objects.requireNonNull(form, "form");
    if (form == Form.NATIVE) {
      if (resultAlias != null)
        throw new IllegalArgumentException(
            "RETURNING result alias does not match its expression scope");
    } else if (resultAlias == DERIVED_ALIAS) {
      resultAlias = resultAlias(selections);
    } else if (!Objects.equals(resultAlias, resultAlias(selections))) {
      throw new IllegalArgumentException(
          "RETURNING result alias does not match its expression scope");
    }
    TableExpression<?> target = targetOf(mutation);
    if (selections.isEmpty()) {
      throw new IllegalArgumentException("RETURNING requires explicit outputs");
    }
    SemanticValidator.validateReturning(target, selections);
    boolean deleteOnly;
    if (mutation instanceof MergeStatement merge) {
      boolean delete = false;
      boolean other = false;
      for (MergeAction action : merge.actions()) {
        if (action.kind() == MergeAction.Kind.DELETE) delete = true;
        else other = true;
      }
      if (delete && other)
        throw new IllegalArgumentException("MERGE RETURNING cannot mix DELETE with other actions");
      deleteOnly = delete;
    } else {
      deleteOnly = mutation instanceof DeleteStatement;
    }
    if (form == Form.OLD_TABLE ? !deleteOnly : form == Form.FINAL_TABLE && deleteOnly) {
      throw new IllegalArgumentException("result-table form does not match mutation semantics");
    }
  }

  public TableExpression<?> target() {
    return targetOf(mutation);
  }

  private static TableExpression<?> targetOf(StatementAst mutation) {
    return switch (mutation) {
      case InsertStatement insert -> insert.target();
      case ConflictInsertStatement insert -> insert.target();
      case MergeStatement merge -> merge.target();
      case UpdateStatement update -> update.target();
      case DeleteStatement delete -> delete.target();
      default -> throw new IllegalArgumentException("RETURNING requires INSERT, UPDATE or DELETE");
    };
  }
}
