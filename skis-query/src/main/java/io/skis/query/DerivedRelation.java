package io.skis.query;

import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.ExpressionPosition;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.QueryClause;
import io.skis.sql.ast.ResolvedExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Framework-owned reusable SELECT source with an explicit, typed public output shape. */
public final class DerivedRelation implements QueryRelation {

  private final SelectQueryState<?> state;
  private final List<DerivedOutput<?>> outputs;
  private final IdentityHashMap<DerivedOutput<?>, Integer> ordinals;
  private final DerivedRelationReference reference;

  DerivedRelation(
      SelectDescription<?> description, Identifier alias, List<DerivedOutput<?>> outputs) {
    SelectDescription<?> checked = Objects.requireNonNull(description, "description");
    this.state = checked.state();
    this.outputs = copyAndValidateOutputs(state, outputs);
    this.ordinals = indexOutputs(this.outputs);
    this.reference = createReference(alias, this.outputs);
    validateCompletedShape(compilePreview());
  }

  /** Explicit relation alias used to qualify every output column. */
  public Identifier alias() {
    return reference.alias();
  }

  /** Ordered immutable output shape. */
  public List<DerivedOutput<?>> outputs() {
    return outputs;
  }

  /** Creates another occurrence over the same SELECT and handles with a different alias. */
  public DerivedRelation as(String alias) {
    return as(Identifier.of(alias));
  }

  /** Creates another occurrence over the same SELECT and handles with a different alias. */
  public DerivedRelation as(Identifier alias) {
    return new DerivedRelation(
        new SelectDescription<>(state), Objects.requireNonNull(alias, "alias"), outputs);
  }

  /** Resolves one nullable output handle that belongs to this relation shape. */
  public <V> Selectable<V> column(DerivedOutput<V> output) {
    int ordinal = requireOrdinal(output);
    return new DerivedColumnSelectable<>(this, output, ordinal);
  }

  /** Resolves one non-null output handle that belongs to this relation shape. */
  public <V> NonNullSelectable<V> column(NonNullDerivedOutput<V> output) {
    int ordinal = requireOrdinal(output);
    return new NonNullDerivedColumnSelectable<>(this, output, ordinal);
  }

  DerivedRelationReference reference() {
    return reference;
  }

  SelectQueryState<?> state() {
    return state;
  }

  DerivedRelationSource compile(QueryConditionCompiler compiler) {
    return new DerivedRelationSource(compiler.subquery(state), reference);
  }

  private SelectStatement compilePreview() {
    try {
      return QueryStructureCompiler.compileSubquery(
          state, new StatementParameterLayout(), new QueryParameterBindings());
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }

  private void validateCompletedShape(SelectStatement statement) {
    QueryBlockAnalysis analysis;
    try {
      analysis = SemanticValidator.analyzeComplete(statement);
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
    for (int index = 0; index < outputs.size(); index++) {
      DerivedOutput<?> output = outputs.get(index);
      Nullability effective = selectionNullability(analysis, index);
      if (!output.nullability().isNullable() && effective.isNullable()) {
        throw new QueryValidationException(
            "derived output '"
                + output.alias().value()
                + "' is effectively nullable after the inner query's complete join chain; "
                + "declare it with Sql.outputNullable(...)");
      }
    }
  }

  private static Nullability selectionNullability(QueryBlockAnalysis analysis, int ordinal) {
    for (ResolvedExpression expression : analysis.expressions()) {
      ExpressionPosition position = expression.position();
      if (position.clause() == QueryClause.SELECT
          && position.itemOrdinal() == ordinal
          && position.operandPath().isEmpty()) {
        return expression.effectiveNullability();
      }
    }
    throw new QueryValidationException(
        "derived query has no resolved visible SELECT output #" + ordinal);
  }

  private int requireOrdinal(DerivedOutput<?> output) {
    Objects.requireNonNull(output, "output");
    Integer ordinal = ordinals.get(output);
    if (ordinal == null) {
      throw new QueryValidationException(
          "derived output handle '"
              + output.alias().value()
              + "' does not belong to relation alias '"
              + alias().value()
              + "'; output membership is matched by handle identity");
    }
    return ordinal;
  }

  private static List<DerivedOutput<?>> copyAndValidateOutputs(
      SelectQueryState<?> state, List<DerivedOutput<?>> outputs) {
    Objects.requireNonNull(outputs, "outputs");
    if (outputs.isEmpty()) {
      throw new QueryValidationException(
          "a derived relation requires at least one explicitly aliased output");
    }
    if (outputs.size() != state.selected().expressions().size()) {
      throw new QueryValidationException(
          "derived relation output shape has "
              + outputs.size()
              + " handles but its SELECT has "
              + state.selected().expressions().size()
              + " visible expressions");
    }
    List<DerivedOutput<?>> copy = new ArrayList<>(outputs.size());
    for (int index = 0; index < outputs.size(); index++) {
      DerivedOutput<?> output = Objects.requireNonNull(outputs.get(index), "derived output");
      if (!state.selected().matchesOutput(index, output.selectable())) {
        throw new QueryValidationException(
            "derived output handle #"
                + index
                + " ('"
                + output.alias().value()
                + "') is not bound to SELECT expression #"
                + index);
      }
      copy.add(output);
    }
    return List.copyOf(copy);
  }

  private static IdentityHashMap<DerivedOutput<?>, Integer> indexOutputs(
      List<DerivedOutput<?>> outputs) {
    IdentityHashMap<DerivedOutput<?>, Integer> indexed = new IdentityHashMap<>();
    for (int index = 0; index < outputs.size(); index++) {
      Integer previous = indexed.put(outputs.get(index), index);
      if (previous != null) {
        throw new QueryValidationException(
            "the same derived output handle is repeated at ordinals " + previous + " and " + index);
      }
    }
    return indexed;
  }

  private static List<DerivedOutputColumn> descriptors(List<DerivedOutput<?>> outputs) {
    return outputs.stream()
        .map(
            output ->
                new DerivedOutputColumn(
                    output.alias(), output.javaType(), output.sqlType(), output.nullability()))
        .toList();
  }

  private static DerivedRelationReference createReference(
      Identifier alias, List<DerivedOutput<?>> outputs) {
    try {
      return new DerivedRelationReference(
          Objects.requireNonNull(alias, "alias"), descriptors(outputs));
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }
}
