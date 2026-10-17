package io.skis.query;

import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Static construction entry point for reusable SQL query descriptions and their inputs. */
public final class Sql {

  private Sql() {}

  /** Selects a complete entity before an independent of root is chosen. */
  public static <E> SelectFromStep<SelectDescription<E>> select(QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return fromStep(
        root ->
            new SelectDescription<>(
                SelectQueryState.create(SelectedResult.entity(selected), root)));
  }

  /** Selects one declared non-null SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<V>> select(NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = Objects.requireNonNull(selectable, "selectable");
    return fromStep(
        root ->
            new SingleColumnSelect<>(
                SelectQueryState.create(SelectedResult.requiredScalar(selected), root)));
  }

  /** Selects one conservatively nullable SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> select(
      Selectable<V> selectable) {
    Selectable<V> selected = Objects.requireNonNull(selectable, "selectable");
    return fromStep(
        root ->
            new SingleColumnSelect<>(
                SelectQueryState.<@Nullable V>create(
                    SelectedResult.nullableScalar(selected), root)));
  }

  /** Selects one generated, fixed-arity result-row shape. */
  public static <R> SelectFromStep<SelectDescription<R>> select(ProjectionSelection<R> projection) {
    ProjectionSelection<R> selected = Objects.requireNonNull(projection, "projection");
    return fromStep(
        root ->
            new SelectDescription<>(
                SelectQueryState.create(SelectedResult.projection(selected), root)));
  }

  /** Explicitly permits a complete entity selection to become nullable after outer joins. */
  public static <E> SelectFromStep<SelectDescription<@Nullable E>> selectNullable(
      QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return fromStep(
        root ->
            new SelectDescription<>(
                SelectQueryState.<@Nullable E>create(
                    SelectedResult.nullableEntity(selected), root)));
  }

  /** Explicitly permits a declared non-null value to become nullable in query context. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = Objects.requireNonNull(selectable, "selectable");
    return fromStep(
        root ->
            new SingleColumnSelect<>(
                SelectQueryState.<@Nullable V>create(
                    SelectedResult.nullableScalar(selected), root)));
  }

  /** Creates the complete-entity SELECT/FROM convenience description. */
  public static <E> SelectDescription<E> selectFrom(QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return select(selected).from(selected);
  }

  public static <V> NonNullDerivedOutput<V> output(NonNullSelectable<V> selectable, String alias) {
    return output(selectable, Identifier.of(alias));
  }

  private static <V> NonNullDerivedOutput<V> output(
      NonNullSelectable<V> selectable, Identifier alias) {
    return new NonNullDerivedOutput<>(
        Objects.requireNonNull(selectable, "selectable"), Objects.requireNonNull(alias, "alias"));
  }

  public static <V> DerivedOutput<V> output(Selectable<V> selectable, String alias) {
    return output(selectable, Identifier.of(alias));
  }

  private static <V> DerivedOutput<V> output(Selectable<V> selectable, Identifier alias) {
    Selectable<V> selected = Objects.requireNonNull(selectable, "selectable");
    return new DerivedOutput<>(
        selected, Objects.requireNonNull(alias, "alias"), selected.nullability());
  }

  public static <V> DerivedOutput<V> outputNullable(NonNullSelectable<V> selectable, String alias) {
    return outputNullable(selectable, Identifier.of(alias));
  }

  private static <V> DerivedOutput<V> outputNullable(
      NonNullSelectable<V> selectable, Identifier alias) {
    return new DerivedOutput<>(
        Objects.requireNonNull(selectable, "selectable"),
        Objects.requireNonNull(alias, "alias"),
        Nullability.NULLABLE);
  }

  public static DerivedRelation derived(
      SelectDescription<?> description, String alias, DerivedOutput<?>... outputs) {
    return derived(description, Identifier.of(alias), outputs);
  }

  private static DerivedRelation derived(
      SelectDescription<?> description, Identifier alias, DerivedOutput<?>... outputs) {
    Objects.requireNonNull(outputs, "outputs");
    return new DerivedRelation(
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
    return new QueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"), Nullability.NULLABLE, null);
  }

  public static <V> QueryParameter<V> parameter(Class<V> javaType, String diagnosticName) {
    return new QueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"),
        Nullability.NULLABLE,
        Objects.requireNonNull(diagnosticName, "diagnosticName"));
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
