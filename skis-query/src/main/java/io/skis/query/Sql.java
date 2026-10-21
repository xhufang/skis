package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Static factories for immutable, framework-owned SQL descriptions. */
public final class Sql {

  private Sql() {}

  /** Selects a complete entity before an independent of root is chosen. */
  public static <E> SelectFromStep<SelectDescription<E>> select(QueryTable<E> table) {
    return io.skis.query.internal.QueryDsl.select(table);
  }

  /** Selects one declared non-null SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<V>> select(NonNullSelectable<V> selectable) {
    return io.skis.query.internal.QueryDsl.select(selectable);
  }

  /** Selects one conservatively nullable SQL value and preserves the one-column result shape. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> select(
      Selectable<V> selectable) {
    return io.skis.query.internal.QueryDsl.select(selectable);
  }

  /** Selects one generated, fixed-arity result-row shape. */
  public static <R> SelectFromStep<SelectDescription<R>> select(ProjectionSelection<R> projection) {
    return io.skis.query.internal.QueryDsl.select(projection);
  }

  /** Explicitly permits a complete entity selection to become nullable after outer joins. */
  public static <E> SelectFromStep<SelectDescription<@Nullable E>> selectNullable(
      QueryTable<E> table) {
    return io.skis.query.internal.QueryDsl.selectNullable(table);
  }

  /** Explicitly permits a declared non-null value to become nullable in query context. */
  public static <V> SelectFromStep<SingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    return io.skis.query.internal.QueryDsl.selectNullable(selectable);
  }

  /** Creates the complete-entity SELECT/FROM convenience description. */
  public static <E> SelectDescription<E> selectFrom(QueryTable<E> table) {
    return io.skis.query.internal.QueryDsl.selectFrom(table);
  }

  public static <V> NonNullDerivedOutput<V> output(NonNullSelectable<V> selectable, String alias) {
    return io.skis.query.internal.QueryDsl.output(selectable, alias);
  }

  public static <V> DerivedOutput<V> output(Selectable<V> selectable, String alias) {
    return io.skis.query.internal.QueryDsl.output(selectable, alias);
  }

  public static <V> DerivedOutput<V> outputNullable(NonNullSelectable<V> selectable, String alias) {
    return io.skis.query.internal.QueryDsl.outputNullable(selectable, alias);
  }

  public static DerivedRelation derived(
      SelectDescription<?> description, String alias, DerivedOutput<?>... outputs) {
    return io.skis.query.internal.QueryDsl.derived(description, alias, outputs);
  }

  public static QueryCondition exists(SelectDescription<?> description) {
    return io.skis.query.internal.QueryDsl.exists(description);
  }

  public static QueryCondition notExists(SelectDescription<?> description) {
    return io.skis.query.internal.QueryDsl.notExists(description);
  }

  public static <V> Selectable<V> scalar(SingleColumnSelect<V> subquery) {
    return io.skis.query.internal.QueryDsl.scalar(subquery);
  }

  public static <V> QueryParameter<V> parameter(Class<V> javaType) {
    return io.skis.query.internal.QueryDsl.parameter(javaType);
  }

  public static <V> QueryParameter<V> parameter(Class<V> javaType, String diagnosticName) {
    return io.skis.query.internal.QueryDsl.parameter(javaType, diagnosticName);
  }
}
