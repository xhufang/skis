package io.skis.query;

import io.skis.sql.ast.Nullability;
import java.util.Objects;

/** Static construction entry point for reusable SQL query descriptions and their inputs. */
public final class Sql {

  private Sql() {}

  /** Selects a complete entity table before an independent FROM root is chosen. */
  public static <R> SelectDescriptionFromStep<NonNullSelectDescription<R>> select(
      QueryTable<R> table) {
    QueryTable<R> selected = Objects.requireNonNull(table, "table");
    return fromStep(
        root ->
            new NonNullSelectDescription<>(
                SelectQueryState.create(SelectedResult.entity(selected), root)));
  }

  /** Selects one declared non-null SQL value and preserves the one-column result shape. */
  public static <V> SelectDescriptionFromStep<NonNullSingleColumnSelect<V>> select(
      NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = Objects.requireNonNull(selectable, "selectable");
    return fromStep(
        root ->
            new NonNullSingleColumnSelect<>(
                SelectQueryState.create(SelectedResult.requiredScalar(selected), root)));
  }

  /** Selects one conservatively nullable SQL value and preserves the one-column result shape. */
  public static <V> SelectDescriptionFromStep<SingleColumnSelect<V>> select(
      Selectable<V> selectable) {
    Selectable<V> selected = Objects.requireNonNull(selectable, "selectable");
    return fromStep(
        root ->
            new SingleColumnSelect<>(
                SelectQueryState.create(SelectedResult.nullableScalar(selected), root)));
  }

  /** Selects one generated, fixed-arity result-row shape. */
  public static <R> SelectDescriptionFromStep<NonNullSelectDescription<R>> select(
      ProjectionSelection<R> projection) {
    ProjectionSelection<R> selected = Objects.requireNonNull(projection, "projection");
    return fromStep(
        root ->
            new NonNullSelectDescription<>(
                SelectQueryState.create(SelectedResult.projection(selected), root)));
  }

  /** Explicitly permits a complete entity selection to become nullable after outer joins. */
  public static <R> SelectDescriptionFromStep<SelectDescription<R>> selectNullable(
      QueryTable<R> table) {
    QueryTable<R> selected = Objects.requireNonNull(table, "table");
    return fromStep(
        root ->
            new SelectDescription<>(
                SelectQueryState.create(SelectedResult.nullableEntity(selected), root)));
  }

  /** Explicitly permits a declared non-null value to become nullable in query context. */
  public static <V> SelectDescriptionFromStep<SingleColumnSelect<V>> selectNullable(
      NonNullSelectable<V> selectable) {
    NonNullSelectable<V> selected = Objects.requireNonNull(selectable, "selectable");
    return fromStep(
        root ->
            new SingleColumnSelect<>(
                SelectQueryState.create(SelectedResult.nullableScalar(selected), root)));
  }

  /** Creates the complete-entity SELECT/FROM convenience description. */
  public static <E> NonNullSelectDescription<E> selectFrom(QueryTable<E> table) {
    QueryTable<E> selected = Objects.requireNonNull(table, "table");
    return select(selected).from(selected);
  }

  /**
   * Tests whether a reusable SELECT description produces at least one row.
   *
   * <p>The description is embedded in the final statement. It is not executed or decoded
   * independently, and its visible selections are preserved exactly.
   */
  public static QueryCondition exists(SelectDescription<?> description) {
    return FrameworkQueryCondition.exists(
        Objects.requireNonNull(description, "description"), false);
  }

  /**
   * Tests whether a reusable SELECT description produces no rows.
   *
   * <p>This is a native {@code NOT EXISTS} node, not a nullable comparison or an IN rewrite.
   */
  public static QueryCondition notExists(SelectDescription<?> description) {
    return FrameworkQueryCondition.exists(Objects.requireNonNull(description, "description"), true);
  }

  /**
   * Embeds a reusable one-column SELECT as one conservatively nullable SQL value expression.
   *
   * <p>The child is not executed independently. Zero rows evaluate to SQL {@code NULL}, one row
   * evaluates to its selected value, and multiple rows remain a database cardinality error. This
   * method never adds an implicit limit or asserts that a row exists.
   */
  public static <V> Selectable<V> scalar(SingleColumnSelect<V> subquery) {
    return new ScalarSubquerySelectable<>(Objects.requireNonNull(subquery, "subquery"));
  }

  /**
   * Creates a nullable query-level parameter reference with no diagnostic name.
   *
   * <p>The reference contains no value or ordinal. Bind its value separately through {@link
   * QueryParameters}.
   */
  public static <V> QueryParameter<V> parameter(Class<V> javaType) {
    return new QueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"), Nullability.NULLABLE, null);
  }

  /** Creates a nullable parameter reference whose name is used only in diagnostics. */
  public static <V> QueryParameter<V> parameter(Class<V> javaType, String diagnosticName) {
    return new QueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"),
        Nullability.NULLABLE,
        Objects.requireNonNull(diagnosticName, "diagnosticName"));
  }

  private static <D> SelectDescriptionFromStep<D> fromStep(
      SelectDescriptionFromStep.Factory<D> factory) {
    return new SelectDescriptionFromStep<>(factory);
  }
}
