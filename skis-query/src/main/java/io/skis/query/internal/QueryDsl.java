package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.DerivedOutput;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullDerivedOutput;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.QueryCondition;
import io.skis.query.QueryParameter;
import io.skis.query.QueryTable;
import io.skis.query.SelectDescription;
import io.skis.query.SelectFromStep;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnSelect;
import io.skis.query.SingleRow;
import io.skis.query.SliceContinuation;
import io.skis.query.SliceRequest;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
      ProjectionMapping<R> mapping,
      List<? extends Selectable<?>> selections) {
    return new DefaultProjectionSelection<>(
        Objects.requireNonNull(mapping, "mapping"),
        List.copyOf(Objects.requireNonNull(selections, "selections")));
  }

  /** Selects a complete entity before an independent of root is chosen. */
  public static <E> SelectFromStep<SelectDescription<E>> select(QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return fromStep(
        root ->
            new DefaultSelectDescription<>(
                SelectQueryState.create(SelectedResult.entity(selected), root)));
  }

  /** Selects one declared non-null SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<V>> select(NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = ExpressionCapture.reusable(selectable);
    return fromStep(
        root ->
            new DefaultSingleColumnSelect<>(
                SelectQueryState.create(SelectedResult.requiredScalar(selected), root)));
  }

  /** Selects one conservatively nullable SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> select(
      Selectable<V> selectable) {
    Selectable<V> selected = ExpressionCapture.reusable(selectable);
    return fromStep(
        root ->
            new DefaultSingleColumnSelect<>(
                SelectQueryState.<@Nullable V>create(
                    SelectedResult.nullableScalar(selected), root)));
  }

  /** Selects one generated, fixed-arity result-row shape. */
  public static <R> SelectFromStep<SelectDescription<R>> select(ProjectionSelection<R> projection) {
    ProjectionSelection<R> selected =
        QueryModels.projection(Objects.requireNonNull(projection, "projection"));
    selected.selections().forEach(ExpressionCapture::reusable);
    return fromStep(
        root ->
            new DefaultSelectDescription<>(
                SelectQueryState.create(SelectedResult.projection(selected), root)));
  }

  /** Explicitly permits a complete entity selection to become nullable after outer joins. */
  public static <E> SelectFromStep<SelectDescription<@Nullable E>> selectNullable(
      QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return fromStep(
        root ->
            new DefaultSelectDescription<>(
                SelectQueryState.<@Nullable E>create(
                    SelectedResult.nullableEntity(selected), root)));
  }

  /** Explicitly permits a declared non-null value to become nullable in query context. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = ExpressionCapture.reusable(selectable);
    return fromStep(
        root ->
            new DefaultSingleColumnSelect<>(
                SelectQueryState.<@Nullable V>create(
                    SelectedResult.nullableScalar(selected), root)));
  }

  /** Creates the complete-entity SELECT/FROM convenience description. */
  public static <E> SelectDescription<E> selectFrom(QueryTable<E> table) {
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
      SelectDescription<?> description, String alias, DerivedOutput<?>... outputs) {
    return derived(
        description,
        identifier(
            alias,
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "derived relation alias is not a portable SQL identifier"),
        outputs);
  }

  private static DerivedRelation derived(
      SelectDescription<?> description, Identifier alias, DerivedOutput<?>... outputs) {
    Objects.requireNonNull(outputs, "outputs");
    return new DefaultDerivedRelation(
        Objects.requireNonNull(description, "description"),
        Objects.requireNonNull(alias, "alias"),
        Arrays.asList(outputs.clone()));
  }

  public static QueryCondition exists(SelectDescription<?> description) {
    return FrameworkQueryCondition.exists(
        Objects.requireNonNull(description, "description"), false);
  }

  public static QueryCondition notExists(SelectDescription<?> description) {
    return FrameworkQueryCondition.exists(Objects.requireNonNull(description, "description"), true);
  }

  public static <V> Selectable<V> scalar(SingleColumnSelect<V> subquery) {
    return new ScalarSubquerySelectable<>(Objects.requireNonNull(subquery, "subquery"));
  }

  public static <V> QueryParameter<V> parameter(Class<V> javaType) {
    return new DefaultQueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"), Nullability.NULLABLE, null);
  }

  public static <V> QueryParameter<V> parameter(Class<V> javaType, String diagnosticName) {
    return new DefaultQueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"),
        Nullability.NULLABLE,
        Objects.requireNonNull(diagnosticName, "diagnosticName"));
  }

  static Identifier identifier(String value, SkisErrorCode code, String detail) {
    Objects.requireNonNull(value, "identifier");
    try {
      return Identifier.of(value);
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(code, failure, detail);
    }
  }

  private static <Q> SelectFromStep<Q> fromStep(Function<QueryRelation, Q> factory) {
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
