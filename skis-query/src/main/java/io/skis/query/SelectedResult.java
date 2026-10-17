package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.sql.ast.SqlExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Query result target kept independent of the FROM root until final scope validation. */
final class SelectedResult<R extends @Nullable Object> {

  private final @Nullable QueryTable<?> table;
  private final @Nullable Selectable<?> scalar;
  private final @Nullable ProjectionSelection<?> projection;
  private final Kind kind;

  private SelectedResult(
      @Nullable QueryTable<?> table,
      @Nullable Selectable<?> scalar,
      @Nullable ProjectionSelection<?> projection,
      Kind kind) {
    this.table = table;
    this.scalar = scalar;
    this.projection = projection;
    this.kind = Objects.requireNonNull(kind, "kind");
    if (kind == Kind.REQUIRED_ENTITY || kind == Kind.NULLABLE_ENTITY) {
      Objects.requireNonNull(table, "table");
      if (scalar != null || projection != null) {
        throw new IllegalArgumentException("an entity result must not carry scalar selections");
      }
    } else if (kind == Kind.GENERATED_PROJECTION) {
      Objects.requireNonNull(projection, "projection");
      if (table != null || scalar != null) {
        throw new IllegalArgumentException(
            "a generated projection result must not be bound to one selected table");
      }
    } else {
      Objects.requireNonNull(scalar, "scalar");
      if (table != null || projection != null) {
        throw new IllegalArgumentException(
            "a scalar result must not be bound to one physical table mapping");
      }
    }
  }

  static <E> SelectedResult<E> entity(QueryTable<E> table) {
    return new SelectedResult<>(table, null, null, Kind.REQUIRED_ENTITY);
  }

  static <E> SelectedResult<@Nullable E> nullableEntity(QueryTable<E> table) {
    return new SelectedResult<>(table, null, null, Kind.NULLABLE_ENTITY);
  }

  static <R> SelectedResult<R> requiredScalar(NonNullSelectable<R> selectable) {
    return new SelectedResult<>(
        null, Objects.requireNonNull(selectable, "selectable"), null, Kind.REQUIRED_SCALAR);
  }

  static <R> SelectedResult<@Nullable R> nullableScalar(Selectable<R> selectable) {
    return new SelectedResult<>(
        null, Objects.requireNonNull(selectable, "selectable"), null, Kind.NULLABLE_SCALAR);
  }

  static <R> SelectedResult<R> projection(ProjectionSelection<R> selection) {
    return new SelectedResult<>(
        null, null, Objects.requireNonNull(selection, "selection"), Kind.GENERATED_PROJECTION);
  }

  /** Derives the cache result contract from the authoritative selected-result variant. */
  Optional<QueryPlanKey.ResultShape> planKeyResultShape(QueryPlanKey.IdentityScope identities) {
    Objects.requireNonNull(identities, "identities");
    return switch (kind) {
      case REQUIRED_ENTITY -> QueryPlanKey.ResultShape.requiredEntity(identities, requireTable());
      case NULLABLE_ENTITY -> QueryPlanKey.ResultShape.nullableEntity(identities, requireTable());
      case REQUIRED_SCALAR -> QueryPlanKey.ResultShape.requiredScalar(identities, requireScalar());
      case NULLABLE_SCALAR -> QueryPlanKey.ResultShape.nullableScalar(identities, requireScalar());
      case GENERATED_PROJECTION ->
          QueryPlanKey.ResultShape.projection(identities, requireProjection());
    };
  }

  ResolvedResultShape<R> resolve(
      TableRuntimeScope scope, List<SqlExpression<?>> compiledExpressions) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(compiledExpressions, "compiledExpressions");
    return switch (kind) {
      case REQUIRED_ENTITY -> entityShape(scope, compiledExpressions, false);
      case NULLABLE_ENTITY -> entityShape(scope, compiledExpressions, true);
      case REQUIRED_SCALAR -> scalarShape(scope, compiledExpressions, false);
      case NULLABLE_SCALAR -> scalarShape(scope, compiledExpressions, true);
      case GENERATED_PROJECTION -> projectionShape(scope, compiledExpressions);
    };
  }

  List<SqlExpression<?>> expressions() {
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY -> List.copyOf(requireTable().selections());
      case REQUIRED_SCALAR, NULLABLE_SCALAR ->
          List.of(SelectableSupport.expression(requireScalar()));
      case GENERATED_PROJECTION ->
          requireProjection().selections().stream()
              .<SqlExpression<?>>map(SelectableSupport::expression)
              .toList();
    };
  }

  List<SqlExpression<?>> compileExpressions(QueryConditionCompiler compiler) {
    Objects.requireNonNull(compiler, "compiler");
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY -> List.copyOf(requireTable().selections());
      case REQUIRED_SCALAR, NULLABLE_SCALAR -> List.of(compiler.expression(requireScalar()));
      case GENERATED_PROJECTION -> {
        List<SqlExpression<?>> expressions =
            new ArrayList<>(requireProjection().selections().size());
        for (Selectable<?> selectable : requireProjection().selections()) {
          expressions.add(compiler.expression(selectable));
        }
        yield List.copyOf(expressions);
      }
    };
  }

  List<SelectableSupport.ExpressionIdentity> expressionIdentities() {
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY ->
          requireTable().selections().stream()
              .map(expression -> new SelectableSupport.ExpressionIdentity(expression, List.of()))
              .toList();
      case REQUIRED_SCALAR, NULLABLE_SCALAR ->
          List.of(SelectableSupport.expressionIdentity(requireScalar()));
      case GENERATED_PROJECTION ->
          requireProjection().selections().stream()
              .map(SelectableSupport::expressionIdentity)
              .toList();
    };
  }

  boolean matchesOutput(int ordinal, Selectable<?> candidate) {
    Objects.requireNonNull(candidate, "candidate");
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY -> {
        QueryTable<?> selectedTable = requireTable();
        if (!(candidate instanceof QueryColumn<?, ?> column)
            || column.table() != selectedTable
            || ordinal < 0
            || ordinal >= selectedTable.entity().properties().size()) {
          yield false;
        }
        yield column.property() == selectedTable.entity().properties().get(ordinal);
      }
      case REQUIRED_SCALAR, NULLABLE_SCALAR ->
          ordinal == 0 && SelectableSupport.sameOccurrence(requireScalar(), candidate);
      case GENERATED_PROJECTION ->
          ordinal >= 0
              && ordinal < requireProjection().selections().size()
              && SelectableSupport.sameOccurrence(
                  requireProjection().selections().get(ordinal), candidate);
    };
  }

  Selectable<?> singleSelectable() {
    if (kind != Kind.REQUIRED_SCALAR && kind != Kind.NULLABLE_SCALAR) {
      throw new IllegalStateException("selected result is not a single SQL value");
    }
    return requireScalar();
  }

  boolean belongsTo(QueryTable<?> candidate) {
    return table == candidate;
  }

  CompiledQueryPlan<R, Object> fastPlan(
      EntityPlanSet<?> rootPlans, CompiledQueryStructure structure) {
    if (!supportsFastPath()) {
      throw new IllegalStateException("only complete non-null entity selections use a Fast Path");
    }
    return entityFastPlan(rootPlans, requireTable(), structure);
  }

  String structuralIdentity() {
    return switch (kind) {
      case REQUIRED_ENTITY -> "entity:" + requireTable().entity().javaType().getName();
      case NULLABLE_ENTITY -> "nullable-entity:" + requireTable().entity().javaType().getName();
      case REQUIRED_SCALAR -> scalarIdentity("scalar:");
      case NULLABLE_SCALAR -> scalarIdentity("nullable-scalar:");
      case GENERATED_PROJECTION -> "projection:" + requireProjection().mappingId();
    };
  }

  /** Returns the compiled expression usable by the current automatic DISTINCT count path. */
  @Nullable SqlExpression<?> automaticDistinctCountExpression(
      boolean hasJoins, List<SqlExpression<?>> compiledExpressions) {
    Objects.requireNonNull(compiledExpressions, "compiledExpressions");
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY -> {
        var primaryKey = requireTable().entity().primaryKey().orElse(null);
        if (primaryKey == null) {
          if (requireTable().selections().size() == 1) {
            yield requireTable().selections().getFirst();
          }
          throw new QueryValidationException(
              "automatic count cannot preserve a multi-expression distinct complete entity "
                  + "without primary-key metadata; provide an explicit count query");
        }
        if (!hasJoins) {
          yield null;
        }
        if (primaryKey.composite()) {
          throw new QueryValidationException(
              "automatic count cannot preserve a distinct complete entity with a composite "
                  + "primary key after JOIN; provide an explicit count query");
        }
        yield requireTable().selections().get(primaryKey.properties().getFirst().ordinal());
      }
      case REQUIRED_SCALAR, NULLABLE_SCALAR -> singleExpression(compiledExpressions);
      case GENERATED_PROJECTION -> {
        if (compiledExpressions.size() != 1) {
          throw new QueryValidationException(
              "automatic count cannot preserve a multi-expression distinct result; provide an "
                  + "explicit count query");
        }
        yield compiledExpressions.getFirst();
      }
    };
  }

  boolean supportsFastPath() {
    return kind == Kind.REQUIRED_ENTITY;
  }

  boolean nullableResult() {
    return kind == Kind.NULLABLE_ENTITY || kind == Kind.NULLABLE_SCALAR;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ResolvedResultShape<R> entityShape(
      TableRuntimeScope scope, List<SqlExpression<?>> compiledExpressions, boolean nullable) {
    if (!compiledExpressions.equals(requireTable().selections())) {
      throw new QueryValidationException(
          "compiled complete-entity selection does not match its table mapping");
    }
    return (ResolvedResultShape)
        (nullable
            ? ResolvedResultShape.nullableEntity((QueryTable) requireTable(), scope)
            : ResolvedResultShape.entity((QueryTable) requireTable(), scope));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ResolvedResultShape<R> scalarShape(
      TableRuntimeScope scope, List<SqlExpression<?>> compiledExpressions, boolean nullable) {
    return (ResolvedResultShape)
        (nullable
            ? ResolvedResultShape.nullableScalar(
                (Selectable) requireScalar(),
                (SqlExpression) singleExpression(compiledExpressions),
                scope)
            : ResolvedResultShape.scalar(
                (Selectable) requireScalar(),
                (SqlExpression) singleExpression(compiledExpressions),
                scope));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ResolvedResultShape<R> projectionShape(
      TableRuntimeScope scope, List<SqlExpression<?>> compiledExpressions) {
    return (ResolvedResultShape)
        ResolvedResultShape.projection(
            (ProjectionSelection) requireProjection(), compiledExpressions, scope);
  }

  private SqlExpression<?> singleExpression(List<SqlExpression<?>> compiledExpressions) {
    if (compiledExpressions.size() != 1) {
      throw new QueryValidationException(
          "compiled scalar selection requires exactly one SQL expression");
    }
    return compiledExpressions.getFirst();
  }

  private String scalarIdentity(String prefix) {
    Selectable<?> selected = requireScalar();
    if (selected instanceof QueryColumn<?, ?> column) {
      return prefix
          + column.table().entity().javaType().getName()
          + ':'
          + column.property().ordinal();
    }
    return prefix
        + SelectableSupport.expression(selected).getClass().getName()
        + ':'
        + selected.javaType().getName()
        + ':'
        + selected.sqlType()
        + ':'
        + SelectableSupport.expression(selected).hashCode();
  }

  private QueryTable<?> requireTable() {
    return Objects.requireNonNull(table, "selected table");
  }

  private Selectable<?> requireScalar() {
    return Objects.requireNonNull(scalar, "selected scalar");
  }

  private ProjectionSelection<?> requireProjection() {
    return Objects.requireNonNull(projection, "projection selection");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <R> CompiledQueryPlan<R, Object> entityFastPlan(
      EntityPlanSet<?> plans, QueryTable<?> table, CompiledQueryStructure structure) {
    return (CompiledQueryPlan)
        plans.selectPlanForStructure(
            (QueryTable) table, Objects.requireNonNull(structure, "structure"));
  }

  private enum Kind {
    REQUIRED_ENTITY,
    NULLABLE_ENTITY,
    REQUIRED_SCALAR,
    NULLABLE_SCALAR,
    GENERATED_PROJECTION
  }
}
