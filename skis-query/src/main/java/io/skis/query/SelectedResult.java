package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.sql.ast.SqlExpression;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query result target kept independent from the FROM root until final scope validation. */
final class SelectedResult<R> {

  private final @Nullable QueryTable<?> table;
  private final @Nullable EntityPlanSet<?> plans;
  private final @Nullable Selectable<R> scalar;
  private final @Nullable ProjectionSelection<R> projection;
  private final Kind kind;

  private SelectedResult(
      @Nullable QueryTable<?> table,
      @Nullable EntityPlanSet<?> plans,
      @Nullable Selectable<R> scalar,
      @Nullable ProjectionSelection<R> projection,
      Kind kind) {
    this.table = table;
    this.plans = plans;
    this.scalar = scalar;
    this.projection = projection;
    this.kind = Objects.requireNonNull(kind, "kind");
    if (kind == Kind.REQUIRED_ENTITY || kind == Kind.NULLABLE_ENTITY) {
      Objects.requireNonNull(table, "table");
      Objects.requireNonNull(plans, "plans");
      if (scalar != null || projection != null) {
        throw new IllegalArgumentException("an entity result must not carry scalar selections");
      }
    } else if (kind == Kind.GENERATED_PROJECTION) {
      Objects.requireNonNull(projection, "projection");
      if (table != null || plans != null || scalar != null) {
        throw new IllegalArgumentException(
            "a generated projection result must not be bound to one selected table");
      }
    } else {
      Objects.requireNonNull(scalar, "scalar");
      if (table != null || plans != null || projection != null) {
        throw new IllegalArgumentException(
            "a scalar result must not be bound to one physical table mapping");
      }
    }
  }

  static <E> SelectedResult<E> entity(QueryTable<E> table, EntityPlanSet<E> plans) {
    return new SelectedResult<>(table, plans, null, null, Kind.REQUIRED_ENTITY);
  }

  static <E> SelectedResult<E> nullableEntity(QueryTable<E> table, EntityPlanSet<E> plans) {
    return new SelectedResult<>(table, plans, null, null, Kind.NULLABLE_ENTITY);
  }

  static <R> SelectedResult<R> requiredScalar(NonNullSelectable<R> selectable) {
    return new SelectedResult<>(
        null, null, Objects.requireNonNull(selectable, "selectable"), null, Kind.REQUIRED_SCALAR);
  }

  static <R> SelectedResult<R> nullableScalar(Selectable<R> selectable) {
    return new SelectedResult<>(
        null, null, Objects.requireNonNull(selectable, "selectable"), null, Kind.NULLABLE_SCALAR);
  }

  static <R> SelectedResult<R> projection(ProjectionSelection<R> selection) {
    return new SelectedResult<>(
        null,
        null,
        null,
        Objects.requireNonNull(selection, "selection"),
        Kind.GENERATED_PROJECTION);
  }

  ResolvedResultShape<R> resolve(TableRuntimeScope scope) {
    Objects.requireNonNull(scope, "scope");
    return switch (kind) {
      case REQUIRED_ENTITY -> entityShape(scope, false);
      case NULLABLE_ENTITY -> entityShape(scope, true);
      case REQUIRED_SCALAR -> ResolvedResultShape.scalar(requireScalar(), scope, false);
      case NULLABLE_SCALAR -> ResolvedResultShape.scalar(requireScalar(), scope, true);
      case GENERATED_PROJECTION -> ResolvedResultShape.projection(requireProjection(), scope);
    };
  }

  List<SqlExpression<?>> expressions() {
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY -> List.copyOf(requireTable().selections());
      case REQUIRED_SCALAR, NULLABLE_SCALAR -> List.of(requireScalar().expression());
      case GENERATED_PROJECTION ->
          requireProjection().selections().stream()
              .<SqlExpression<?>>map(Selectable::expression)
              .toList();
    };
  }

  boolean belongsTo(QueryTable<?> candidate) {
    return table == candidate;
  }

  CompiledQueryPlan<R, Object> fastPlan(CompiledQueryStructure structure) {
    if (!supportsFastPath()) {
      throw new IllegalStateException("only complete non-null entity selections use a Fast Path");
    }
    return entityFastPlan(requirePlans(), requireTable(), structure);
  }

  String structuralIdentity() {
    return switch (kind) {
      case REQUIRED_ENTITY -> "entity:" + requirePlans().entity().javaType().getName();
      case NULLABLE_ENTITY -> "nullable-entity:" + requirePlans().entity().javaType().getName();
      case REQUIRED_SCALAR -> scalarIdentity("scalar:");
      case NULLABLE_SCALAR -> scalarIdentity("nullable-scalar:");
      case GENERATED_PROJECTION -> "projection:" + requireProjection().mappingId();
    };
  }

  /** Returns the single expression usable by the current automatic DISTINCT count path. */
  @Nullable SqlExpression<?> automaticDistinctCountExpression(boolean hasJoins) {
    return switch (kind) {
      case REQUIRED_ENTITY, NULLABLE_ENTITY -> {
        var primaryKey = requirePlans().entity().primaryKey().orElse(null);
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
      case REQUIRED_SCALAR, NULLABLE_SCALAR -> requireScalar().expression();
      case GENERATED_PROJECTION -> {
        List<SqlExpression<?>> expressions = expressions();
        if (expressions.size() != 1) {
          throw new QueryValidationException(
              "automatic count cannot preserve a multi-expression distinct result; provide an "
                  + "explicit count query");
        }
        yield expressions.getFirst();
      }
    };
  }

  boolean supportsFastPath() {
    return kind == Kind.REQUIRED_ENTITY;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ResolvedResultShape<R> entityShape(TableRuntimeScope scope, boolean nullable) {
    return (ResolvedResultShape)
        ResolvedResultShape.entity(
            (QueryTable) requireTable(), (EntityPlanSet) requirePlans(), scope, nullable);
  }

  private String scalarIdentity(String prefix) {
    Selectable<R> selected = requireScalar();
    if (selected instanceof QueryColumn<?, ?> column) {
      return prefix
          + column.table().entity().javaType().getName()
          + ':'
          + column.property().ordinal();
    }
    return prefix
        + selected.expression().getClass().getName()
        + ':'
        + selected.javaType().getName()
        + ':'
        + selected.sqlType()
        + ':'
        + selected.expression().hashCode();
  }

  private QueryTable<?> requireTable() {
    return Objects.requireNonNull(table, "selected table");
  }

  private EntityPlanSet<?> requirePlans() {
    return Objects.requireNonNull(plans, "selected table plans");
  }

  private Selectable<R> requireScalar() {
    return Objects.requireNonNull(scalar, "selected scalar");
  }

  private ProjectionSelection<R> requireProjection() {
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
