package io.skis.query.internal;

import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.metadata.PropertyMeta;
import io.skis.query.*;
import io.skis.sql.ast.*;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Invocation preparation: one dense parameter namespace for all write clauses. */
public final class WriteInputCompiler {
  private final EntityRuntimeRegistry registry;
  private final QueryTable<?> target;
  private final StatementParameterLayout layout;
  private final QueryParameterBindings bindings = new QueryParameterBindings();
  private final QueryConditionCompiler compiler;

  public WriteInputCompiler(EntityRuntimeRegistry registry, QueryTable<?> target, int prefix) {
    this.registry = registry;
    this.target = target;
    layout = new StatementParameterLayout(true, prefix);
    compiler = new QueryConditionCompiler(layout, bindings);
  }

  public ColumnExpression<?, ?> column(PropertyMeta<?, ?> property) {
    return resolveColumn(target, property).expression();
  }

  public Selectable<?> selectable(PropertyMeta<?, ?> property) {
    return resolveColumn(target, property);
  }

  private static <E, V> QueryColumn<E, V> resolveColumn(
      QueryTable<E> target, PropertyMeta<?, V> property) {
    // internalColumn verifies canonical property identity before returning the typed column.
    @SuppressWarnings("unchecked")
    PropertyMeta<E, V> owned = (PropertyMeta<E, V>) property;
    return QueryTableModel.of(target).internalColumn(owned);
  }

  public SqlPredicate condition(@Nullable QueryCondition condition) {
    return condition == null
        ? new BooleanPredicate(true)
        : QueryConditions.compile(condition, compiler);
  }

  public SqlExpression<?> expression(Selectable<?> value) {
    return compiler.expression(value);
  }

  public List<UpdateAssignment<?>> assignments(
      List<MutationQuerySupport.Assignment> values, boolean version) {
    List<UpdateAssignment<?>> result = new ArrayList<>(values.size() + 1);
    for (var value : values) result.add(MutationQuerySupport.assignment(target, value, compiler));
    if (version) target.entity().version().ifPresent(v -> result.add(increment(v.property())));
    return List.copyOf(result);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private UpdateAssignment<?> increment(PropertyMeta<?, ?> property) {
    ColumnExpression column = column(property);
    return new UpdateAssignment(column, new IncrementExpression(column));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public List<SqlExpression<?>> values(
      List<? extends Selectable<?>> columns, @Nullable Object[] values) {
    if (columns.size() != values.length)
      throw new IllegalArgumentException("column/value arity mismatch");
    List<SqlExpression<?>> result = new ArrayList<>(values.length);
    for (int i = 0; i < values.length; i++) {
      Selectable column = columns.get(i);
      QueryParameter parameter =
          new DefaultQueryParameter<>(column.javaType(), column.nullability(), null);
      // Public values() has already captured mutable JDBC inputs; do not deep-copy again.
      compiler.include(QueryParameters.ofOwnedSnapshot(parameter, values[i]));
      result.add(compiler.parameter(column, parameter));
    }
    return List.copyOf(result);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public ParameterRelationSource source(ValuesSource source) {
    ValuesSources.Source owned = ValuesSources.require(source);
    List<ParameterSlot<?>> parameters = new ArrayList<>(owned.outputs.size());
    for (int i = 0; i < owned.outputs.size(); i++) {
      DerivedOutput output = owned.outputs.get(i);
      Selectable template = ((DefaultDerivedOutput) output).selectable();
      QueryParameter parameter =
          new DefaultQueryParameter<>(output.javaType(), output.nullability(), null);
      compiler.include(QueryParameters.ofOwnedSnapshot(parameter, owned.values[i]));
      parameters.add(compiler.parameter(template, parameter));
    }
    return new ParameterRelationSource(parameters, owned.reference);
  }

  public List<ColumnExpression<?, ?>> columns(List<? extends Selectable<?>> columns) {
    List<ColumnExpression<?, ?>> result = new ArrayList<>(columns.size());
    for (Selectable<?> column : columns) result.add(MutationQuerySupport.column(target, column));
    return List.copyOf(result);
  }

  public MutationQuerySupport.Inputs finish() {
    var sources = layout.parameterSources();
    var slots = layout.parameterSlots();
    List<JdbcTypeCodec<?>> codecs = new ArrayList<>(sources.size());
    List<Object> identities = new ArrayList<>(sources.size());
    var scope =
        TableRuntimeScope.resolve(registry, FromClause.of(MutationQuerySupport.table(target)));
    for (Selectable<?> source : layout.builtinParameterSources())
      ResolvedValueMapping.requireBuiltinParameterSource(source, scope.outputCodec(source));
    boolean cacheable = true;
    var identityScope = new QueryPlanKey.IdentityScope(registry);
    for (int i = 0; i < sources.size(); i++) {
      Selectable<?> source = sources.get(i);
      codecs.add(scope.outputCodec(source));
      Optional<?> identity = QueryPlanKey.ParameterShape.codec(identityScope, slots.get(i), source);
      cacheable &= identity.isPresent();
      identity.ifPresent(identities::add);
    }
    var parameters = bindings.parameters();
    parameters.validateFor(layout.parameterReferences());
    return new MutationQuerySupport.Inputs(
        List.of(),
        new BooleanPredicate(true),
        List.copyOf(codecs),
        cacheable ? Optional.of(List.copyOf(identities)) : Optional.empty(),
        parameters.projectValues(layout.parameterReferences()));
  }
}
