package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.DerivedOutput;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullDerivedOutput;
import io.skis.query.NonNullSelectable;
import io.skis.query.SelectQuery;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.sql.ast.ColumnExpression;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Framework-owned reusable SELECT source with an explicit, typed public output shape. */
final class DefaultDerivedRelation implements DerivedRelation {

  private final SelectQueryState<?> state;
  private final QueryParameters parameters;
  private final List<DerivedOutput<?>> outputs;
  private final IdentityHashMap<DerivedOutput<?>, Integer> ordinals;
  private final Map<Object, Integer> selectedOrdinals;
  private final DerivedRelationReference reference;
  private final List<Selectable<?>> columns;
  private final QueryRelation queryRelation;

  DefaultDerivedRelation(SelectQuery<?> source, Identifier alias, List<DerivedOutput<?>> outputs) {
    this(
        QueryModels.description(source).state,
        QueryModels.description(source).parameters,
        alias,
        copyAndValidateOutputs(QueryModels.description(source).state, outputs));
    validateCompletedShape(compilePreview());
  }

  DefaultDerivedRelation(SelectSource<?> source, Identifier alias) {
    this(
        QueryModels.description(source).state,
        QueryModels.description(source).parameters,
        alias,
        QueryModels.description(source).state.inferredOutputs());
  }

  private DefaultDerivedRelation(
      SelectQueryState<?> state,
      QueryParameters parameters,
      Identifier alias,
      List<DerivedOutput<?>> outputs) {
    this.state = state;
    this.parameters = parameters;
    this.outputs = outputs;
    if (parameters.isEmpty()) {
      this.ordinals = indexOutputs(outputs);
      this.selectedOrdinals = indexSelections(state.selected().outputSelectables());
      this.reference = createReference(alias, outputs);
      this.columns = createColumns(outputs);
      this.queryRelation = new DerivedQueryRelation(this);
    } else {
      // Columns and structural relations must never retain their source's invocation values.
      DefaultDerivedRelation structure =
          new DefaultDerivedRelation(state, QueryParameters.empty(), alias, outputs);
      this.ordinals = structure.ordinals;
      this.selectedOrdinals = structure.selectedOrdinals;
      this.reference = structure.reference;
      this.columns = structure.columns;
      this.queryRelation = structure.queryRelation;
    }
  }

  QueryParameters parameters() {
    return parameters;
  }

  /** Infers output handles; callers use the memoized {@link SelectQueryState#inferredOutputs()}. */
  static List<DerivedOutput<?>> inferOutputs(SelectQueryState<?> state) {
    SelectStatement statement =
        QueryStructureCompiler.compileSubquery(
            state, new StatementParameterLayout(), new QueryParameterBindings());
    QueryBlockAnalysis analysis = SemanticValidator.analyzeComplete(statement);
    List<Selectable<?>> selected = state.selected().outputSelectables();
    @Nullable Nullability[] nullabilities = selectionNullabilities(analysis, selected.size());
    List<DerivedOutput<?>> outputs = new ArrayList<>(selected.size());
    Set<String> names = new HashSet<>();
    for (int index = 0; index < selected.size(); index++) {
      Selectable<?> item = selected.get(index);
      Identifier alias = outputAlias(item);
      if (!names.add(alias.value()))
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_DERIVED_SHAPE, "duplicate derived output alias: " + alias.value());
      outputs.add(inferredOutput(item, alias, Objects.requireNonNull(nullabilities[index])));
    }
    return List.copyOf(outputs);
  }

  private static Identifier outputAlias(Selectable<?> item) {
    if (item instanceof ExpressionSelectable<?> expression && expression.outputAlias() != null)
      return expression.outputAlias();
    var expression = SelectableSupport.expression(item);
    if (expression instanceof ColumnExpression<?, ?> column)
      return Identifier.of(column.property().column().name());
    if (item instanceof DerivedColumnSelectable<?> column)
      return QueryModels.output(column.output()).aliasIdentifier();
    throw QueryValidationException.of(
        SkisErrorCode.QUERY_DERIVED_SHAPE, "a computed derived output requires a SELECT alias");
  }

  private static <V> DerivedOutput<V> inferredOutput(
      Selectable<V> item, Identifier alias, Nullability effective) {
    if (!effective.isNullable() && item instanceof NonNullSelectable<?> nonNull) {
      @SuppressWarnings("unchecked")
      NonNullSelectable<V> typed = (NonNullSelectable<V>) nonNull;
      return new DefaultNonNullDerivedOutput<>(typed, alias);
    }
    return new DefaultDerivedOutput<>(item, alias, effective);
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
        QueryDsl.identifier(
            alias,
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived relation alias is not a portable SQL identifier"));
  }

  /** Creates another occurrence over the same SELECT and handles with a different alias. */
  DerivedRelation as(Identifier alias) {
    return new DefaultDerivedRelation(
        state, parameters, Objects.requireNonNull(alias, "alias"), outputs);
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

  @Override
  public <V> Selectable<V> column(Selectable<V> selected) {
    return columnAt(requireSelected(selected));
  }

  @Override
  public <V> NonNullSelectable<V> column(NonNullSelectable<V> selected) {
    int ordinal = requireSelected(selected);
    if (!(columns.get(ordinal) instanceof NonNullSelectable<?>))
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_NULLABILITY_CONTRACT,
          "selected source expression is effectively nullable; use columnNullable");
    return nonNullColumnAt(ordinal);
  }

  @Override
  public <V> Selectable<V> columnNullable(NonNullSelectable<V> selected) {
    return columnAt(requireSelected(selected));
  }

  private int requireSelected(Selectable<?> selected) {
    SelectableSupport.requireFramework(selected);
    Integer found = selectedOrdinals.get(selectionKey(selected));
    if (found == null)
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_DERIVED_SHAPE,
          "expression is not selected by this source occurrence");
    if (found < 0)
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_DERIVED_SHAPE,
          "selected source expression is ambiguous; name each SELECT occurrence");
    return found;
  }

  private static Map<Object, Integer> indexSelections(List<Selectable<?>> selected) {
    Map<Object, Integer> indexed = new HashMap<>(selected.size());
    for (int ordinal = 0; ordinal < selected.size(); ordinal++) {
      Object key = selectionKey(selected.get(ordinal));
      if (indexed.putIfAbsent(key, ordinal) != null) indexed.put(key, -1);
    }
    return Map.copyOf(indexed);
  }

  private static Object selectionKey(Selectable<?> selected) {
    return switch (selected) {
      case QueryColumn<?, ?> column -> new SelectionIdentity(column.table(), column.property());
      case DerivedColumnSelectable<?> column ->
          new SelectionIdentity(
              QueryModels.relation(column.relation()).reference(), column.output());
      case ExpressionSelectable<?> expression when expression.outputAlias() != null ->
          expression.compilationIdentity();
      case ExpressionSelectable<?> expression -> SelectableSupport.expressionIdentity(expression);
      case ScalarSubquerySelectable<?> scalar -> SelectableSupport.expressionIdentity(scalar);
      default -> selected;
    };
  }

  private record SelectionIdentity(Object source, Object column) {
    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof SelectionIdentity(Object sour, Object col)
          && source == sour
          && column == col;
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(source) + System.identityHashCode(column);
    }
  }

  DerivedRelationReference reference() {
    return reference;
  }

  QueryRelation queryRelation() {
    return queryRelation;
  }

  DerivedRelationSource compile(QueryConditionCompiler compiler) {
    compiler.include(parameters);
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
    @Nullable Nullability[] nullabilities = selectionNullabilities(analysis, outputs.size());
    for (int index = 0; index < outputs.size(); index++) {
      DerivedOutput<?> output = outputs.get(index);
      Nullability effective = Objects.requireNonNull(nullabilities[index]);
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

  private static @Nullable Nullability[] selectionNullabilities(
      QueryBlockAnalysis analysis, int size) {
    // Unresolved slots remain null until their visible SELECT expressions are indexed.
    @Nullable Nullability[] results = new @Nullable Nullability[size];
    for (ResolvedExpression expression : analysis.expressions()) {
      ExpressionPosition position = expression.position();
      if (position.clause() == QueryClause.SELECT
          && position.itemOrdinal() < size
          && position.operandPath().isEmpty()) {
        results[position.itemOrdinal()] = expression.effectiveNullability();
      }
    }
    for (int ordinal = 0; ordinal < size; ordinal++) {
      if (results[ordinal] == null)
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived query has no resolved visible SELECT output #" + ordinal);
    }
    return results;
  }

  private int requireOrdinal(DerivedOutput<?> output) {
    QueryModels.output(Objects.requireNonNull(output, "output"));
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
      if (!state.selected().matchesOutput(index, QueryModels.selectable(output))) {
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
            "the same derived output handle is repeated at ordinals " + previous + " and " + index);
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
                    QueryModels.output(output).aliasIdentifier(),
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
