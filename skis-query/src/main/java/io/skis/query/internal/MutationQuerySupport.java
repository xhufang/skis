package io.skis.query.internal;

import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.RowDecoder;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import io.skis.query.QueryParameter;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.UpdateAssignment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Internal mutation bridge to the framework-owned DSL, codecs and generated projection compiler.
 */
public final class MutationQuerySupport {

  private MutationQuerySupport() {}

  public static <E> TableExpression<E> table(QueryTable<E> table) {
    return QueryTableModel.of(table).tableExpression();
  }

  public static <E, V> ColumnExpression<E, V> column(QueryTable<E> target, Selectable<V> value) {
    if (!(value instanceof QueryColumn<?, ?> column) || column.table() != target) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_PARAMETER_SHAPE,
          "mutation column must belong to its target table occurrence");
    }
    @SuppressWarnings("unchecked")
    ColumnExpression<E, V> expression = (ColumnExpression<E, V>) column.expression();
    return expression;
  }

  /** Captures one typed input, including mutable JDBC values, at its “row.value” call. */
  public static <V> @Nullable Object captureValue(Selectable<V> column, @Nullable V value) {
    if (value == null ? !column.nullability().isNullable() : !column.javaType().isInstance(value))
      throw new IllegalArgumentException("VALUES input violates its declared type or nullability");
    return value == null ? null : QueryValueSnapshots.capture(value);
  }

  public static final class Assignment {
    private final Selectable<?> column;
    private final @Nullable Selectable<?> expression;
    private final @Nullable QueryParameter<?> parameter;
    private final QueryParameters parameters;

    public Assignment(
        Selectable<?> column, @Nullable Object value, @Nullable Selectable<?> expression) {
      this.column = SelectableSupport.requireFramework(Objects.requireNonNull(column, "column"));
      this.expression = expression;
      if (expression == null) {
        QueryParameter<?> reference = parameter(column);
        this.parameter = reference;
        this.parameters = capture(reference, value);
      } else {
        this.parameter = null;
        this.parameters = QueryParameters.empty();
      }
    }

    private static <V> QueryParameter<V> parameter(Selectable<V> column) {
      return new DefaultQueryParameter<>(column.javaType(), column.nullability(), null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static QueryParameters capture(QueryParameter<?> parameter, @Nullable Object value) {
      return QueryParameters.of((QueryParameter) parameter, value);
    }

    public Selectable<?> column() {
      return column;
    }

    public @Nullable Selectable<?> expression() {
      return expression;
    }

    @Override
    public String toString() {
      return "MutationAssignment[values redacted]";
    }
  }

  public record Inputs(
      List<UpdateAssignment<?>> assignments,
      SqlPredicate where,
      List<JdbcTypeCodec<?>> codecs,
      Optional<List<Object>> cacheIdentity,
      @Nullable Object[] values) {
    @Override
    public String toString() {
      return "MutationInputs[values redacted]";
    }
  }

  /** Captures values separately from dense logical slots, including repeated expression inputs. */
  public static <E> Inputs inputs(
      EntityRuntimeRegistry registry,
      QueryTable<E> target,
      List<Assignment> assignments,
      @Nullable QueryCondition condition,
      boolean update) {
    StatementParameterLayout layout = new StatementParameterLayout();
    QueryParameterBindings bindings = new QueryParameterBindings();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    List<UpdateAssignment<?>> compiled = new ArrayList<>(assignments.size() + 1);
    for (Assignment assignment : assignments) {
      compiled.add(assignment(target, assignment, compiler));
    }
    if (update) {
      target
          .entity()
          .version()
          .ifPresent(
              version ->
                  compiled.add(
                      increment(QueryTableModel.of(target).internalColumn(version.property()))));
    }
    SqlPredicate where =
        condition == null
            ? new BooleanPredicate(true)
            : QueryConditions.compile(condition, compiler);
    TableRuntimeScope scope = TableRuntimeScope.resolve(registry, FromClause.of(table(target)));
    for (Selectable<?> source : layout.builtinParameterSources()) {
      ResolvedValueMapping.requireBuiltinParameterSource(source, scope.outputCodec(source));
    }
    var sources = layout.parameterSources();
    var slots = layout.parameterSlots();
    var references = layout.parameterReferences();
    List<JdbcTypeCodec<?>> codecs = new ArrayList<>(sources.size());
    List<Object> identities = new ArrayList<>();
    var identityScope = new QueryPlanKey.IdentityScope(registry);
    boolean cacheable = true;
    for (int index = 0; index < sources.size(); index++) {
      Selectable<?> source = sources.get(index);
      codecs.add(scope.outputCodec(source));
      var identity = QueryPlanKey.ParameterShape.codec(identityScope, slots.get(index), source);
      cacheable &= identity.isPresent();
      identity.ifPresent(identities::add);
    }
    QueryParameters parameters = bindings.parameters();
    parameters.validateFor(references);
    return new Inputs(
        List.copyOf(compiled),
        where,
        List.copyOf(codecs),
        cacheable ? Optional.of(List.copyOf(identities)) : Optional.empty(),
        parameters.projectValues(references));
  }

  private static <V> UpdateAssignment<V> increment(QueryColumn<?, V> column) {
    return new UpdateAssignment<>(
        column.expression(), new IncrementExpression<>(column.expression()));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  static UpdateAssignment<?> assignment(
      QueryTable<?> target, Assignment input, QueryConditionCompiler compiler) {
    ColumnExpression column = column(target, input.column());
    SqlExpression expression;
    Selectable<?> selected = input.expression();
    if (selected == null) {
      QueryParameter parameter = Objects.requireNonNull(input.parameter);
      compiler.include(input.parameters);
      expression = compiler.parameter(input.column(), parameter);
    } else {
      expression = compiler.expression(selected);
    }
    return new UpdateAssignment<>(column, expression);
  }

  public static <E> Result<E> result(QueryTable<E> target) {
    return new Result<>(
        target,
        SelectedResult.entity(target),
        List.copyOf(QueryTableModel.of(target).selections()));
  }

  /** Validates a collection return selection without allocating an unused result descriptor. */
  public static <R> void validateReturningProjection(
      QueryTable<?> target, ProjectionSelection<R> projection) {
    ProjectionSelection<R> selected = QueryModels.projection(projection);
    for (Selectable<?> selectable : selected.selections()) {
      column(target, selectable);
    }
  }

  /** Resolves dedicated projection sources and checks that they all belong to the write target. */
  public static <R> void validateReturningProjection(
      QueryTable<?> target, ProjectionType<R> projection) {
    validateReturningProjection(
        target, ProjectionSourceBinder.bind(projection, QueryRelations.of(target), List.of()));
  }

  public static <R> Result<R> result(QueryTable<?> target, ProjectionType<R> projection) {
    return result(
        target, ProjectionSourceBinder.bind(projection, QueryRelations.of(target), List.of()));
  }

  public static <R> Result<R> result(QueryTable<?> target, ProjectionSelection<R> projection) {
    ProjectionSelection<R> selected = QueryModels.projection(projection);
    List<ColumnExpression<?, ?>> columns = new ArrayList<>(selected.selections().size());
    for (Selectable<?> selectable : selected.selections()) {
      columns.add(column(target, selectable));
    }
    return new Result<>(target, SelectedResult.projection(selected), List.copyOf(columns));
  }

  /** Value-free return selection; generated decoder creation is deferred until a cache miss. */
  public static final class Result<R> {
    private final QueryTable<?> target;
    private final SelectedResult<R> selected;
    private final List<ColumnExpression<?, ?>> columns;

    private Result(
        QueryTable<?> target, SelectedResult<R> selected, List<ColumnExpression<?, ?>> columns) {
      this.target = target;
      this.selected = selected;
      this.columns = columns;
    }

    /** Registry-owned Codec sources, result/factory contracts and loader-aware type tokens. */
    public Optional<?> cacheIdentity(EntityRuntimeRegistry registry) {
      return selected.planKeyResultShape(new QueryPlanKey.IdentityScope(registry));
    }

    public List<ColumnExpression<?, ?>> columns() {
      return columns;
    }

    public RowDecoder<R> decoder(EntityRuntimeRegistry registry) {
      TableRuntimeScope scope = TableRuntimeScope.resolve(registry, FromClause.of(table(target)));
      RowDecoder<R> decoder = selected.resolve(scope, new ArrayList<>(columns)).decoder();
      return (resultSet, context) -> {
        R value = decoder.decode(resultSet, context);
        if (value == null) {
          throw MutationException.of(
              SkisErrorCode.MUTATION_RETURNING_SHAPE, "generated RETURNING decoder returned null");
        }
        return value;
      };
    }

    @Override
    public String toString() {
      return "MutationReturnSelection[values redacted]";
    }
  }
}
