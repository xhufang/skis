package io.skis.query.internal;

import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.RowDecoder;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.RowColumns;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.RowAssignment;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SetAssignment;
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

  public static <V> Assignment setNull(Selectable<V> column) {
    if (!column.nullability().isNullable())
      throw new IllegalArgumentException("SET NULL requires a nullable target");
    return new Assignment(
        column, null, new ExpressionSelectable<>(LiteralExpression.nullLiteral(column.javaType())));
  }

  public static <V> Assignment setDefault(Selectable<V> column) {
    return new Assignment(
        column,
        null,
        new ExpressionSelectable<>(LiteralExpression.defaultValue(column.javaType())));
  }

  public static final class Assignment {
    private final Selectable<?> column;
    private final @Nullable Selectable<?> expression;
    private final @Nullable QueryParameter<?> parameter;
    private final QueryParameters parameters;
    private final @Nullable TargetRowColumns row;
    private final @Nullable AbstractSelectQuery<?, ?> rowQuery;

    public Assignment(
        Selectable<?> column, @Nullable Object value, @Nullable Selectable<?> expression) {
      this.column = SelectableSupport.requireFramework(Objects.requireNonNull(column, "column"));
      this.expression = expression;
      this.row = null;
      this.rowQuery = null;
      if (expression == null) {
        QueryParameter<?> reference = parameter(column);
        this.parameter = reference;
        this.parameters = capture(reference, value);
      } else {
        this.parameter = null;
        this.parameters = QueryParameters.empty();
      }
    }

    private Assignment(TargetRowColumns row, AbstractSelectQuery<?, ?> query) {
      this.row = row;
      this.rowQuery = query;
      this.column = row.columns().getFirst();
      this.expression = null;
      this.parameter = null;
      this.parameters = query.parameters;
    }

    public List<Selectable<?>> columns() {
      return row == null ? List.of(column) : row.columns();
    }

    private static <V> QueryParameter<V> parameter(Selectable<V> column) {
      return new DefaultQueryParameter<>(column.javaType(), column.nullability());
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
      List<? extends SetAssignment> assignments,
      SqlPredicate where,
      List<JdbcTypeCodec<?>> codecs,
      Optional<List<Object>> cacheIdentity,
      @Nullable Object[] values,
      @Nullable FromClause sources,
      List<SqlExpression<?>> returningExpressions) {
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
      @Nullable Sources sourceTree,
      @Nullable Result<?> returning) {
    StatementParameterLayout layout = new StatementParameterLayout();
    QueryParameterBindings bindings = new QueryParameterBindings();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    FromClause sourceAst = sourceTree == null ? null : sourceTree.compile(compiler);
    List<SetAssignment> compiled = new ArrayList<>(assignments.size());
    for (Assignment assignment : assignments) {
      compiled.add(setAssignment(target, assignment, compiler));
    }
    SqlPredicate where =
        condition == null
            ? new BooleanPredicate(true)
            : QueryConditions.compile(condition, compiler);
    List<SqlExpression<?>> outputs = returning == null ? List.of() : returning.compile(compiler);
    TableRuntimeScope scope = TableRuntimeScope.resolve(registry, sourceScope(target, sourceAst));
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
        parameters.projectValues(references),
        sourceAst,
        outputs);
  }

  private static FromClause sourceScope(QueryTable<?> target, @Nullable FromClause sources) {
    if (sources == null) return FromClause.of(table(target));
    List<JoinClause> joins = new ArrayList<>(sources.joins());
    joins.add(new JoinClause(JoinType.CROSS, table(target), null));
    return new FromClause(sources.root(), joins);
  }

  public static Sources source(QueryTable<?> table) {
    return new Sources(QueryRelations.of(table), List.of(), QueryParameters.empty());
  }

  public static Sources source(DerivedRelation relation) {
    QueryRelation source = QueryRelations.of(relation);
    return new Sources(
        QueryRelations.structure(source), List.of(), QueryRelations.parameters(source));
  }

  /** Immutable, value-separated source tree shared with SELECT construction. */
  public static final class Sources {
    private final QueryRelation root;
    private final List<QueryJoin> joins;
    private final QueryParameters parameters;

    private Sources(QueryRelation root, List<QueryJoin> joins, QueryParameters parameters) {
      this.root = root;
      this.joins = joins;
      this.parameters = parameters;
    }

    public Sources join(QueryTable<?> table, JoinType type, @Nullable QueryCondition on) {
      return append(QueryRelations.of(table), type, on);
    }

    public Sources join(DerivedRelation relation, JoinType type, @Nullable QueryCondition on) {
      return append(QueryRelations.of(relation), type, on);
    }

    private Sources append(QueryRelation relation, JoinType type, @Nullable QueryCondition on) {
      ExpressionCapture capture = new ExpressionCapture();
      capture.include(QueryRelations.parameters(relation));
      QueryCondition condition = on == null ? null : capture.condition(on);
      List<QueryJoin> next = new ArrayList<>(joins.size() + 1);
      next.addAll(joins);
      next.add(new QueryJoin(type, QueryRelations.structure(relation), condition));
      return new Sources(root, List.copyOf(next), parameters.merge(capture.parameters()));
    }

    FromClause compile(QueryConditionCompiler compiler) {
      compiler.include(parameters);
      List<JoinClause> compiled = new ArrayList<>(joins.size());
      var source = QueryRelations.compile(root, compiler);
      for (QueryJoin join : joins) {
        var right = QueryRelations.compile(join.right(), compiler);
        SqlPredicate on = join.on() == null ? null : QueryConditions.compile(join.on(), compiler);
        compiled.add(new JoinClause(join.type(), right, on));
      }
      return new FromClause(source, compiled);
    }
  }

  public static Assignment rowAssignment(RowColumns columns, SelectSource<?> query) {
    if (!(columns instanceof TargetRowColumns row))
      throw new IllegalArgumentException("target row was not created by SKIS");
    return new Assignment(row, QueryModels.description(query));
  }

  /** Query-fed writes use the same value provenance and built-in Codec rules as SELECT results. */
  public static List<JdbcTypeCodec<?>> queryOutputCodecs(
      EntityRuntimeRegistry registry,
      SelectStatement query,
      @Nullable FromClause outerSources,
      List<JdbcTypeCodec<?>> parameterCodecs) {
    return QueryInputMappings.outputCodecs(registry, query, outerSources, parameterCodecs);
  }

  static SetAssignment setAssignment(
      QueryTable<?> target, Assignment input, QueryConditionCompiler compiler) {
    if (input.row == null) return assignment(target, input, compiler);
    compiler.include(input.parameters);
    List<ColumnExpression<?, ?>> columns = new ArrayList<>(input.row.columns().size());
    for (Selectable<?> selected : input.row.columns()) columns.add(column(target, selected));
    return new RowAssignment(
        columns, compiler.subquery(Objects.requireNonNull(input.rowQuery).state));
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
    return new Result<>(target, SelectedResult.entity(target));
  }

  public static <R> Result<R> result(QueryTable<?> target, ProjectionType<R> projection) {
    return result(
        target, ProjectionSourceBinder.bind(projection, QueryRelations.of(target), List.of()));
  }

  public static <R> Result<R> result(QueryTable<?> target, ProjectionSelection<R> projection) {
    return new Result<>(target, SelectedResult.projection(QueryModels.projection(projection)));
  }

  public static <V> Result<V> result(QueryTable<?> target, NonNullSelectable<V> selection) {
    return new Result<>(target, SelectedResult.requiredScalar(selection));
  }

  public static <V> Result<@Nullable V> result(QueryTable<?> target, Selectable<V> selection) {
    SelectedResult<@Nullable V> selected = SelectedResult.nullableScalar(selection);
    return new Result<@Nullable V>(target, selected);
  }

  /** Invocation-owned values and a detached result model; shared plans retain only its decoder. */
  public static final class Result<R extends @Nullable Object> {
    private final QueryTable<?> target;
    private final SelectedResult<R> selected;
    private final QueryParameters parameters;

    private Result(QueryTable<?> target, SelectedResult<R> selection) {
      this.target = target;
      ExpressionCapture capture = new ExpressionCapture();
      this.selected = selection.capture(capture);
      this.parameters = capture.parameters();
    }

    List<SqlExpression<?>> compile(QueryConditionCompiler compiler) {
      compiler.include(parameters);
      List<SqlExpression<?>> outputs = selected.compileExpressions(compiler, false);
      SemanticValidator.validateReturning(table(target), outputs);
      return outputs;
    }

    public Optional<?> cacheIdentity(EntityRuntimeRegistry registry) {
      return selected.planKeyResultShape(new QueryPlanKey.IdentityScope(registry));
    }

    public RowDecoder<R> decoder(
        EntityRuntimeRegistry registry, List<SqlExpression<?>> expressions) {
      TableRuntimeScope scope = TableRuntimeScope.resolve(registry, FromClause.of(table(target)));
      return selected.resolve(scope, expressions).decoder();
    }

    @Override
    public String toString() {
      return "MutationReturnSelection[values redacted]";
    }
  }
}
