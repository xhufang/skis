package io.skis.query;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
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
public final class DerivedRelation {

  private final SelectQueryState<?> state;
  private final List<DerivedOutput<?>> outputs;
  private final IdentityHashMap<DerivedOutput<?>, Integer> ordinals;
  private final DerivedRelationReference reference;
  private final List<Selectable<?>> columns;
  private final QueryRelation queryRelation;

  DerivedRelation(
      SelectDescription<?> description, Identifier alias, List<DerivedOutput<?>> outputs) {
    SelectDescription<?> checked = Objects.requireNonNull(description, "description");
    this.state = checked.state();
    this.outputs = copyAndValidateOutputs(state, outputs);
    this.ordinals = indexOutputs(this.outputs);
    this.reference = createReference(alias, this.outputs);
    this.columns = createColumns(this.outputs);
    this.queryRelation = new DerivedQueryRelation(this);
    validateCompletedShape(compilePreview());
  }

  /** Explicit relation alias used to qualify every output column. */
  public String alias() {
    return reference.alias().value();
  }

  /** Ordered immutable output shape. */
  public List<DerivedOutput<?>> outputs() {
    return outputs;
  }

  /** Creates another occurrence over the same SELECT and handles with a different alias. */
  public DerivedRelation as(String alias) {
    return as(
        Sql.identifier(
            alias,
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived relation alias is not a portable SQL identifier"));
  }

  /** Creates another occurrence over the same SELECT and handles with a different alias. */
  DerivedRelation as(Identifier alias) {
    return new DerivedRelation(
        new SelectDescription<>(state), Objects.requireNonNull(alias, "alias"), outputs);
  }

  /** Resolves one nullable output handle that belongs to this relation shape. */
  public <V> Selectable<V> column(DerivedOutput<V> output) {
    int ordinal = requireOrdinal(output);
    return columnAt(ordinal);
  }

  /** Resolves one non-null output handle that belongs to this relation shape. */
  public <V> NonNullSelectable<V> column(NonNullDerivedOutput<V> output) {
    int ordinal = requireOrdinal(output);
    return nonNullColumnAt(ordinal);
  }

  DerivedRelationReference reference() {
    return reference;
  }

  QueryRelation queryRelation() {
    return queryRelation;
  }

  DerivedRelationSource compile(QueryConditionCompiler compiler) {
    return new DerivedRelationSource(compiler.subquery(state), reference);
  }

  private SelectStatement compilePreview() {
    try {
      return QueryStructureCompiler.compileSubquery(
          state, new StatementParameterLayout(), new QueryParameterBindings());
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_DERIVED_SHAPE, failure, "derived SELECT preview compilation failed");
    }
  }

  private void validateCompletedShape(SelectStatement statement) {
    QueryBlockAnalysis analysis;
    try {
      analysis = SemanticValidator.analyzeComplete(statement);
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_DERIVED_SHAPE, failure, "derived SELECT semantic validation failed");
    }
    for (int index = 0; index < outputs.size(); index++) {
      DerivedOutput<?> output = outputs.get(index);
      Nullability effective = selectionNullability(analysis, index);
      if (!output.nullability().isNullable() && effective.isNullable()) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_NULLABILITY_CONTRACT,
            "derived output '"
                + output.alias()
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
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_DERIVED_SHAPE,
        "derived query has no resolved visible SELECT output #" + ordinal);
  }

  private int requireOrdinal(DerivedOutput<?> output) {
    Objects.requireNonNull(output, "output");
    Integer ordinal = ordinals.get(output);
    if (ordinal == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_DERIVED_SHAPE,
          "derived output handle '"
              + output.alias()
              + "' does not belong to relation alias '"
              + alias()
              + "'; output membership is matched by handle identity");
    }
    return ordinal;
  }

  private static List<DerivedOutput<?>> copyAndValidateOutputs(
      SelectQueryState<?> state, List<DerivedOutput<?>> outputs) {
    Objects.requireNonNull(outputs, "outputs");
    if (outputs.isEmpty()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_DERIVED_SHAPE,
          "a derived relation requires at least one explicitly aliased output");
    }
    if (outputs.size() != state.selected().expressions().size()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_DERIVED_SHAPE,
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
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived output handle #"
                + index
                + " ('"
                + output.alias()
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
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "the same derived output handle is repeated at ordinals "
                + previous
                + " and "
                + index);
      }
    }
    return indexed;
  }

  private List<Selectable<?>> createColumns(List<DerivedOutput<?>> outputs) {
    List<Selectable<?>> created = new ArrayList<>(outputs.size());
    for (int ordinal = 0; ordinal < outputs.size(); ordinal++) {
      created.add(createColumn(outputs.get(ordinal), ordinal));
    }
    return List.copyOf(created);
  }

  private <V> Selectable<V> createColumn(DerivedOutput<V> output, int ordinal) {
    if (output instanceof NonNullDerivedOutput<?> nonNullOutput) {
      @SuppressWarnings("unchecked")
      NonNullDerivedOutput<V> typedOutput = (NonNullDerivedOutput<V>) nonNullOutput;
      return new NonNullDerivedColumnSelectable<>(this, typedOutput, ordinal);
    }
    return new DerivedColumnSelectable<>(this, output, ordinal);
  }

  @SuppressWarnings("unchecked")
  private <V> Selectable<V> columnAt(int ordinal) {
    return (Selectable<V>) columns.get(ordinal);
  }

  @SuppressWarnings("unchecked")
  private <V> NonNullSelectable<V> nonNullColumnAt(int ordinal) {
    return (NonNullSelectable<V>) columns.get(ordinal);
  }

  private static List<DerivedOutputColumn> descriptors(List<DerivedOutput<?>> outputs) {
    return outputs.stream()
        .map(
            output ->
                new DerivedOutputColumn(
                    output.aliasIdentifier(),
                    output.javaType(),
                    output.sqlType(),
                    output.nullability()))
        .toList();
  }

  private static DerivedRelationReference createReference(
      Identifier alias, List<DerivedOutput<?>> outputs) {
    try {
      return new DerivedRelationReference(
          Objects.requireNonNull(alias, "alias"), descriptors(outputs));
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_DERIVED_SHAPE,
          failure,
          "derived relation reference construction failed");
    }
  }
}
