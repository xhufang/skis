package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.DerivedOutput;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullDerivedOutput;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.Row2;
import io.skis.query.Row3;
import io.skis.query.RowColumns;
import io.skis.query.SelectFromStep;
import io.skis.query.SelectQuery;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import io.skis.query.SingleColumnSelect;
import io.skis.query.SingleRow;
import io.skis.query.SliceContinuation;
import io.skis.query.SliceRequest;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Static construction entry point for reusable SQL query descriptions and their inputs. */
public final class QueryDsl {

  private QueryDsl() {}

  public static <R extends @Nullable Object> SingleRow<R> noRow() {
    return DefaultSingleRow.noRow();
  }

  public static <R extends @Nullable Object> SingleRow<R> presentRow(R value) {
    return DefaultSingleRow.present(value);
  }

  public static <R> SingleRow<R> requiredRow(R value) {
    return DefaultSingleRow.of(value);
  }

  public static <R> SingleRow<R> nullableRow(@Nullable R value) {
    return DefaultSingleRow.ofNullable(value);
  }

  public static SliceRequest offsetSlice(long offset, int pageSize) {
    return DefaultSliceRequest.offset(offset, pageSize);
  }

  public static SliceRequest firstKeysetSlice(int pageSize) {
    return DefaultSliceRequest.keysetFirst(pageSize);
  }

  public static SliceRequest resumeSlice(SliceContinuation continuation, int pageSize) {
    return DefaultSliceRequest.resume(continuation, pageSize);
  }

  /** Direct construction used by the generated projection facade. */
  public static <R> ProjectionSelection<R> projection(
      ProjectionMapping<R> mapping, List<? extends Selectable<?>> selections) {
    return new DefaultProjectionSelection<>(
        Objects.requireNonNull(mapping, "mapping"),
        List.copyOf(Objects.requireNonNull(selections, "selections")));
  }

  /** Selects a complete entity before an independent of root is chosen. */
  public static <E> SelectFromStep<SelectQuery<E>> select(QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return fromStep(root -> model(SelectedResult.entity(selected), root));
  }

  /** Selects one declared non-null SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<V>> select(NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = SelectableSupport.requireFramework(selectable);
    return fromStep(root -> single(SelectedResult.requiredScalar(selected), root));
  }

  /** Selects one conservatively nullable SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> select(
      Selectable<V> selectable) {
    Selectable<V> selected = SelectableSupport.requireFramework(selectable);
    return fromStep(
        root -> QueryDsl.<@Nullable V>single(SelectedResult.nullableScalar(selected), root));
  }

  /** Selects one generated, fixed-arity result-row shape. */
  public static <R> SelectFromStep<SelectQuery<R>> select(ProjectionSelection<R> projection) {
    ProjectionSelection<R> selected =
        QueryModels.projection(Objects.requireNonNull(projection, "projection"));
    return fromStep(root -> model(SelectedResult.projection(selected), root));
  }

  /** Explicitly permits a complete entity selection to become nullable after outer joins. */
  public static <E> SelectFromStep<SelectQuery<@Nullable E>> selectNullable(QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return fromStep(
        root -> QueryDsl.<@Nullable E>model(SelectedResult.nullableEntity(selected), root));
  }

  /** Explicitly permits a declared non-null value to become nullable in query context. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = SelectableSupport.requireFramework(selectable);
    return fromStep(
        root -> QueryDsl.<@Nullable V>single(SelectedResult.nullableScalar(selected), root));
  }

  /** Creates the complete-entity SELECT/FROM convenience description. */
  public static <E> SelectQuery<E> selectFrom(QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return select(selected).from(selected);
  }

  public static <V> NonNullDerivedOutput<V> output(NonNullSelectable<V> selectable, String alias) {
    return output(
        selectable,
        identifier(
            alias,
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived output alias is not a portable SQL identifier"));
  }

  private static <V> NonNullDerivedOutput<V> output(
      NonNullSelectable<V> selectable, Identifier alias) {
    return new DefaultNonNullDerivedOutput<>(
        Objects.requireNonNull(selectable, "selectable"), Objects.requireNonNull(alias, "alias"));
  }

  public static <V> DerivedOutput<V> output(Selectable<V> selectable, String alias) {
    return output(
        selectable,
        identifier(
            alias,
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived output alias is not a portable SQL identifier"));
  }

  private static <V> DerivedOutput<V> output(Selectable<V> selectable, Identifier alias) {
    Selectable<V> selected = SelectableSupport.requireFramework(selectable);
    return new DefaultDerivedOutput<>(
        selected, Objects.requireNonNull(alias, "alias"), selected.nullability());
  }

  public static <V> DerivedOutput<V> outputNullable(NonNullSelectable<V> selectable, String alias) {
    return outputNullable(
        selectable,
        identifier(
            alias,
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived output alias is not a portable SQL identifier"));
  }

  private static <V> DerivedOutput<V> outputNullable(
      NonNullSelectable<V> selectable, Identifier alias) {
    return new DefaultDerivedOutput<>(
        Objects.requireNonNull(selectable, "selectable"),
        Objects.requireNonNull(alias, "alias"),
        Nullability.NULLABLE);
  }

  public static DerivedRelation derived(
      SelectQuery<?> description, String alias, DerivedOutput<?>... outputs) {
    return derived(
        description,
        identifier(
            alias,
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived relation alias is not a portable SQL identifier"),
        outputs);
  }

  private static DerivedRelation derived(
      SelectQuery<?> description, Identifier alias, DerivedOutput<?>... outputs) {
    Objects.requireNonNull(outputs, "outputs");
    return new DefaultDerivedRelation(
        Objects.requireNonNull(description, "description"),
        Objects.requireNonNull(alias, "alias"),
        Arrays.asList(outputs.clone()));
  }

  public static QueryCondition exists(SelectSource<?> description) {
    return FrameworkQueryCondition.exists(
        Objects.requireNonNull(description, "description"), false);
  }

  public static QueryCondition notExists(SelectSource<?> description) {
    return FrameworkQueryCondition.exists(Objects.requireNonNull(description, "description"), true);
  }

  @SuppressWarnings("Convert2Diamond")
  public static <V> Selectable<V> scalar(SingleColumnQuery<? extends @Nullable V> subquery) {
    return new ScalarSubquerySelectable<V>(Objects.requireNonNull(subquery, "subquery"));
  }

  @SuppressWarnings("Convert2Diamond")
  private static <R extends @Nullable Object> DefaultSelectQuery<R> model(
      SelectedResult<R> selected, QueryRelation root) {
    return SelectQueryState.entry(
        selected, root, (state, values) -> new DefaultSelectQuery<R>(state, values, Set.of()));
  }

  @SuppressWarnings("Convert2Diamond")
  private static <R extends @Nullable Object> DefaultSingleColumnSelect<R> single(
      SelectedResult<R> selected, QueryRelation root) {
    return SelectQueryState.entry(
        selected,
        root,
        (state, values) -> new DefaultSingleColumnSelect<R>(state, values, Set.of()));
  }

  public static <R> SelectFromStep<SelectQuery<R>> select(ProjectionType<R> projection) {
    Objects.requireNonNull(projection, "projection");
    return fromStep(root -> model(SelectedResult.specialProjection(projection), root));
  }

  public static <V> Selectable<V> alias(Selectable<V> source, String alias) {
    SelectableSupport.requireFramework(source);
    return new ExpressionSelectable<>(
        SelectableSupport.expression(source),
        aliasRecipe(source),
        identifier(alias, SkisErrorCode.QUERY_DERIVED_SHAPE, "invalid output alias"));
  }

  public static <V> NonNullSelectable<V> alias(NonNullSelectable<V> source, String alias) {
    SelectableSupport.requireFramework(source);
    return new NonNullExpressionSelectable<>(
        SelectableSupport.expression(source),
        aliasRecipe(source),
        identifier(alias, SkisErrorCode.QUERY_DERIVED_SHAPE, "invalid output alias"));
  }

  @SuppressWarnings("unchecked")
  private static <V> ExpressionSelectable.Recipe<V> aliasRecipe(Selectable<V> source) {
    return new ExpressionSelectable.Recipe<>(
        (compiler, operands, condition) -> (SqlExpression<V>) operands.getFirst(),
        List.of(source),
        null,
        QueryParameters.empty());
  }

  static DerivedRelation source(SelectSource<?> source, String alias) {
    return new DefaultDerivedRelation(
        source, identifier(alias, SkisErrorCode.QUERY_DERIVED_SHAPE, "invalid source alias"));
  }

  public static <A, B> SelectFromStep<SelectQuery<Row2<@Nullable A, @Nullable B>>> select(
      Selectable<A> first, Selectable<B> second) {
    return select(QueryDsl.<@Nullable A, @Nullable B>row2(first, second, 3));
  }

  public static <A, B> SelectFromStep<SelectQuery<Row2<A, @Nullable B>>> select(
      NonNullSelectable<A> first, Selectable<B> second) {
    return select(QueryDsl.<A, @Nullable B>row2(first, second, 2));
  }

  public static <A, B> SelectFromStep<SelectQuery<Row2<@Nullable A, B>>> select(
      Selectable<A> first, NonNullSelectable<B> second) {
    return select(QueryDsl.<@Nullable A, B>row2(first, second, 1));
  }

  public static <A, B> SelectFromStep<SelectQuery<Row2<A, B>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second) {
    return select(QueryDsl.row2(first, second, 0));
  }

  public static <A, B, C>
      SelectFromStep<SelectQuery<Row3<@Nullable A, @Nullable B, @Nullable C>>> select(
          Selectable<A> first, Selectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<@Nullable A, @Nullable B, @Nullable C>row3(first, second, third, 7));
  }

  public static <A, B, C> SelectFromStep<SelectQuery<Row3<A, @Nullable B, @Nullable C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<A, @Nullable B, @Nullable C>row3(first, second, third, 6));
  }

  public static <A, B, C> SelectFromStep<SelectQuery<Row3<@Nullable A, B, @Nullable C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<@Nullable A, B, @Nullable C>row3(first, second, third, 5));
  }

  public static <A, B, C> SelectFromStep<SelectQuery<Row3<A, B, @Nullable C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<A, B, @Nullable C>row3(first, second, third, 4));
  }

  public static <A, B, C> SelectFromStep<SelectQuery<Row3<@Nullable A, @Nullable B, C>>> select(
      Selectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.<@Nullable A, @Nullable B, C>row3(first, second, third, 3));
  }

  public static <A, B, C> SelectFromStep<SelectQuery<Row3<A, @Nullable B, C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.<A, @Nullable B, C>row3(first, second, third, 2));
  }

  public static <A, B, C> SelectFromStep<SelectQuery<Row3<@Nullable A, B, C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.<@Nullable A, B, C>row3(first, second, third, 1));
  }

  public static <A, B, C> SelectFromStep<SelectQuery<Row3<A, B, C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.row3(first, second, third, 0));
  }

  public static <A extends @Nullable Object, B extends @Nullable Object>
      ProjectionSelection<Row2<A, B>> row2(
          Selectable<?> first, Selectable<?> second, int nullableMask) {
    return ProjectionMapping.<A, B>row2(first, second, nullableMask).bind(first, second);
  }

  public static <A extends @Nullable Object, B extends @Nullable Object, C extends @Nullable Object>
      ProjectionSelection<Row3<A, B, C>> row3(
          Selectable<?> first, Selectable<?> second, Selectable<?> third, int nullableMask) {
    return ProjectionMapping.<A, B, C>row3(first, second, third, nullableMask)
        .bind(first, second, third);
  }

  public static RowColumns row(
      Selectable<?> first, Selectable<?> second, Selectable<?>... remaining) {
    List<Selectable<?>> columns = new ArrayList<>(remaining.length + 2);
    columns.add(SelectableSupport.requireFramework(first));
    columns.add(SelectableSupport.requireFramework(second));
    for (Selectable<?> column : remaining) columns.add(SelectableSupport.requireFramework(column));
    return new TargetRowColumns(List.copyOf(columns));
  }

  static Identifier identifier(String value, SkisErrorCode code, String detail) {
    Objects.requireNonNull(value, "identifier");
    try {
      return Identifier.of(value);
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(code, failure, detail);
    }
  }

  static <Q> SelectFromStep<Q> fromStep(Function<QueryRelation, Q> factory) {
    return new DescriptionFromStep<>(factory);
  }
}

/** Internal generic FROM stage shared by every reusable description shape. */
final class DescriptionFromStep<Q> implements SelectFromStep<Q> {

  private final Function<QueryRelation, Q> factory;

  DescriptionFromStep(Function<QueryRelation, Q> factory) {
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  @Override
  public Q from(QueryTable<?> table) {
    return factory.apply(QueryRelations.of(table));
  }

  @Override
  public Q from(DerivedRelation relation) {
    return factory.apply(QueryRelations.of(relation));
  }
}

record TargetRowColumns(List<Selectable<?>> columns) implements RowColumns {}
