package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.*;
import io.skis.sql.ast.*;
import java.util.*;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Framework-owned VALUES occurrences and conflict expression construction. */
public final class ValuesSources {

  private ValuesSources() {}

  public static ValuesSource create(Consumer<ValuesRow> values) {
    Row row = new Row();
    try {
      Objects.requireNonNull(values, "values").accept(row);
      return occurrence(row.outputs, row.values.toArray(), "s");
    } finally {
      row.open = false;
    }
  }

  private static final class Row implements ValuesRow {
    final List<DerivedOutput<?>> outputs = new ArrayList<>();
    final List<@Nullable Object> values = new ArrayList<>();
    boolean open = true;

    @Override
    public <V> ValuesRow value(NonNullDerivedOutput<V> output, V value) {
      return value((DerivedOutput<V>) output, Objects.requireNonNull(value, "value"));
    }

    @Override
    public <V> ValuesRow value(DerivedOutput<V> output, @Nullable V value) {
      if (!open)
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "VALUES row collector is only valid during its callback");
      if (!(output instanceof DefaultDerivedOutput<?>)
          || (value == null
              ? !output.nullability().isNullable()
              : !output.javaType().isInstance(value)))
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_DERIVED_SHAPE,
            "VALUES input violates its declared handle, type or nullability");
      Object captured = value == null ? null : QueryValueSnapshots.capture(value);
      outputs.add(output);
      values.add(captured);
      return this;
    }

    @Override
    public String toString() {
      return "ValuesRowCollector[values redacted]";
    }
  }

  private static Source occurrence(
      List<DerivedOutput<?>> outputs, @Nullable Object[] values, String alias) {
    try {
      return new Source(outputs, values, alias);
    } catch (IllegalArgumentException failure) {
      throw QueryValidationException.ofCause(
          SkisErrorCode.QUERY_DERIVED_SHAPE, failure, "VALUES source violates its declared shape");
    }
  }

  public static <V> Selectable<V> excluded(Selectable<V> column) {
    if (!(column instanceof QueryColumn<?, V> physical)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE, "EXCLUDED requires a physical target column");
    }
    return wrap(new ExcludedExpression<>(physical.expression()), column);
  }

  public static <V> NonNullSelectable<V> excluded(NonNullSelectable<V> column) {
    return (NonNullSelectable<V>) excluded((Selectable<V>) column);
  }

  private static <V> Selectable<V> wrap(SqlExpression<V> descriptor, Selectable<V> template) {
    return descriptor.nullable()
        ? new ExpressionSelectable<>(descriptor, template)
        : new NonNullExpressionSelectable<>(descriptor, template);
  }

  static Source require(ValuesSource source) {
    if (!(source instanceof Source owned))
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_DERIVED_SHAPE, "VALUES source was not created by SKIS");
    return owned;
  }

  static final class Source implements ValuesSource {
    final List<DerivedOutput<?>> outputs;
    final @Nullable Object[] values;
    final DerivedRelationReference reference;
    final IdentityHashMap<DerivedOutput<?>, Integer> ordinals;
    final List<Selectable<?>> columns;

    Source(List<DerivedOutput<?>> outputs, @Nullable Object[] values, String alias) {
      this.outputs = List.copyOf(outputs);
      this.values = values;
      if (outputs.size() != values.length || outputs.isEmpty())
        throw new IllegalArgumentException("VALUES shape mismatch");
      ordinals = new IdentityHashMap<>();
      List<DerivedOutputColumn> shape = new ArrayList<>(outputs.size());
      for (int i = 0; i < outputs.size(); i++) {
        DerivedOutput<?> output = outputs.get(i);
        if (!(output instanceof DefaultDerivedOutput<?>) || ordinals.put(output, i) != null)
          throw new IllegalArgumentException("VALUES requires distinct framework output handles");
        if (values[i] == null
            ? !output.nullability().isNullable()
            : !output.javaType().isInstance(values[i]))
          throw new IllegalArgumentException(
              "VALUES value violates its declared type or nullability");
        shape.add(
            new DerivedOutputColumn(
                Identifier.of(output.alias()),
                output.javaType(),
                output.sqlType(),
                output.nullability()));
      }
      reference = new DerivedRelationReference(Identifier.of(alias), shape);
      List<Selectable<?>> selected = new ArrayList<>(outputs.size());
      for (int i = 0; i < outputs.size(); i++) selected.add(makeColumn(outputs.get(i), i));
      columns = List.copyOf(selected);
    }

    private <V> Selectable<V> makeColumn(DerivedOutput<V> output, int ordinal) {
      return wrap(
          new DerivedColumnExpression<>(reference, ordinal),
          ((DefaultDerivedOutput<V>) output).selectable());
    }

    @Override
    public ValuesSource as(String alias) {
      return occurrence(outputs, values, alias);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> Selectable<V> column(DerivedOutput<V> output) {
      Integer ordinal = ordinals.get(output);
      if (ordinal == null)
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_DERIVED_SHAPE, "output handle belongs to another VALUES shape");
      return (Selectable<V>) columns.get(ordinal);
    }

    @Override
    public <V> NonNullSelectable<V> column(NonNullDerivedOutput<V> output) {
      return (NonNullSelectable<V>) column((DerivedOutput<V>) output);
    }

    @Override
    public String toString() {
      return "ValuesSource[values redacted]";
    }
  }
}
