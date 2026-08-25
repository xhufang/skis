package io.skis.query;

import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.JdbcTypeCodec;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowDecoder;
import io.skis.mapping.RowReadContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, reflection-free mapping from selected query columns to a user result type.
 *
 * <p>Applications obtain user-type projections from APT-generated {@code *Projection} classes. SKIS
 * resolves the generated JDBC codec for every selected property while compiling the query and reads
 * result-set values by their one-based column indexes.
 */
public final class Projection<E, R> {

  private static final Mapping<Object> SCALAR_MAPPING = new Mapping<>(ScalarMapping.class);

  private final Mapping<R> mapping;
  private final List<QueryColumn<E, ?>> columns;
  private final DecoderFactory<E, R> decoderFactory;

  private Projection(
      Mapping<R> mapping,
      List<? extends QueryColumn<E, ?>> columns,
      DecoderFactory<E, R> decoderFactory) {
    this.mapping = Objects.requireNonNull(mapping, "mapping");
    Objects.requireNonNull(columns, "columns");
    if (columns.isEmpty()) {
      throw new QueryValidationException("a projection must select at least one column");
    }
    List<QueryColumn<E, ?>> copy = new ArrayList<>(columns.size());
    for (QueryColumn<E, ?> column : columns) {
      copy.add(Objects.requireNonNull(column, "column"));
    }
    this.columns = List.copyOf(copy);
    this.decoderFactory = Objects.requireNonNull(decoderFactory, "decoderFactory");
    requireOneTableExpression(this.columns);
  }

  /**
   * Creates one opaque, strongly typed identity for an APT-generated projection mapper.
   *
   * <p>The generated mapper stores the returned token in a private static final field. Keeping the
   * result type on the token prevents two unrelated decoders from sharing one cached plan through a
   * caller-selected raw class key.
   */
  public static <R> Mapping<R> mapping(Class<?> mappingType) {
    return new Mapping<>(mappingType);
  }

  /**
   * Infrastructure entry used by APT-generated projection mappers.
   *
   * <p>Application code should use the generated {@code *Projection.of(...)} method rather than
   * calling this method directly.
   */
  public static <E, R> Projection<E, R> generated(
      Mapping<R> mapping,
      List<? extends QueryColumn<E, ?>> columns,
      DecoderFactory<E, R> decoderFactory) {
    return new Projection<>(mapping, columns, decoderFactory);
  }

  static <E, V> Projection<E, V> scalar(QueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    if (column.nullable()) {
      throw new QueryValidationException(
          "nullable scalar column '"
              + column.property().name()
              + "' requires a generated non-null projection result");
    }
    return generated(
        scalarMapping(),
        List.of(column),
        readers -> {
          ValueReader<V> value = readers.reader(0, column);
          return (resultSet, context) -> requireResult(value.read(resultSet, context));
        });
  }

  Mapping<R> mapping() {
    return mapping;
  }

  List<QueryColumn<E, ?>> columns() {
    return columns;
  }

  RowDecoder<R> rowDecoder(EntityRuntimeModel<E> model) {
    Objects.requireNonNull(model, "model");
    List<ProjectionValue<?>> values = new ArrayList<>(columns.size());
    for (int index = 0; index < columns.size(); index++) {
      QueryColumn<E, ?> column = columns.get(index);
      int ordinal = column.property().ordinal();
      if (ordinal < 0 || ordinal >= model.properties().size()) {
        throw foreignColumn(model, column);
      }
      PropertyRuntime<?, ?> runtime = model.properties().get(ordinal);
      if (runtime.property() != column.property()) {
        throw foreignColumn(model, column);
      }
      values.add(projectionValue(runtime.codec(), index + 1, column.nullable()));
    }
    Readers<E> readers = new CompiledReaders<>(columns, List.copyOf(values));
    RowDecoder<R> decoder =
        Objects.requireNonNull(decoderFactory.create(readers), "projection row decoder");
    return (resultSet, context) -> requireResult(decoder.decode(resultSet, context));
  }

  void validateFrom(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    for (QueryColumn<E, ?> column : columns) {
      if (!column.expression().table().equals(table)) {
        throw new QueryValidationException(
            "projection column '"
                + column.property().name()
                + "' belongs to a different table expression than from");
      }
    }
  }

  private static <E> void requireOneTableExpression(List<QueryColumn<E, ?>> columns) {
    QueryColumn<E, ?> first = columns.getFirst();
    for (int index = 1; index < columns.size(); index++) {
      QueryColumn<E, ?> column = columns.get(index);
      if (!column.expression().table().equals(first.expression().table())) {
        throw new QueryValidationException(
            "a single-table projection accepts columns from one table expression");
      }
    }
  }

  private static QueryValidationException foreignColumn(
      EntityRuntimeModel<?> model, QueryColumn<?, ?> column) {
    return new QueryValidationException(
        "projection column '"
            + column.property().name()
            + "' does not belong to runtime model '"
            + model.entity().entityName()
            + "'");
  }

  private static <V> ProjectionValue<V> projectionValue(
      JdbcTypeCodec<V> codec, int resultIndex, boolean nullable) {
    return new ProjectionValue<>(codec, resultIndex, nullable);
  }

  private static <R> R requireResult(@Nullable R result) throws SQLException {
    if (result == null) {
      throw new SQLException("projection mapper returned null");
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static <V> Mapping<V> scalarMapping() {
    return (Mapping<V>) (Mapping<?>) SCALAR_MAPPING;
  }

  /** Opaque identity tying one generated mapper to its declared result type. */
  public static final class Mapping<R> {

    private final Class<?> mappingType;

    private Mapping(Class<?> mappingType) {
      this.mappingType = Objects.requireNonNull(mappingType, "mappingType");
    }

    /** Returns the generated mapper class represented by this identity. */
    public Class<?> mappingType() {
      return mappingType;
    }
  }

  /**
   * Builds one immutable row decoder from the readers resolved for generated projection columns.
   */
  @FunctionalInterface
  public interface DecoderFactory<E, R> {
    RowDecoder<R> create(Readers<E> readers);
  }

  /** Typed reader lookup used while an APT-generated projection decoder is being assembled. */
  public interface Readers<E> {

    /** Resolves the typed reader at one zero-based projection selection index. */
    <V> ValueReader<V> reader(int selectionIndex, QueryColumn<E, V> column);
  }

  /** Reads one generated projection value without advancing the result-set cursor. */
  @FunctionalInterface
  public interface ValueReader<V> {
    @Nullable V read(ResultSet resultSet, RowReadContext context) throws SQLException;
  }

  private record CompiledReaders<E>(
      List<QueryColumn<E, ?>> columns, List<ProjectionValue<?>> values) implements Readers<E> {

    @Override
    @SuppressWarnings("unchecked")
    public <V> ValueReader<V> reader(int selectionIndex, QueryColumn<E, V> column) {
      Objects.requireNonNull(column, "column");
      if (selectionIndex < 0 || selectionIndex >= columns.size()) {
        throw new QueryValidationException(
            "projection selection index "
                + selectionIndex
                + " is outside [0, "
                + columns.size()
                + ")");
      }
      if (columns.get(selectionIndex) != column) {
        throw new QueryValidationException(
            "generated projection reader at index "
                + selectionIndex
                + " does not match column '"
                + column.property().name()
                + "'");
      }
      return (ValueReader<V>) values.get(selectionIndex);
    }
  }

  private record ProjectionValue<V>(JdbcTypeCodec<V> codec, int index, boolean nullable)
      implements ValueReader<V> {

    private ProjectionValue {
      Objects.requireNonNull(codec, "codec");
      if (index < 1) {
        throw new IllegalArgumentException("projection result index must be positive");
      }
    }

    @Override
    public @Nullable V read(ResultSet resultSet, RowReadContext context) throws SQLException {
      V value = codec.read(resultSet, index, context);
      if (value == null && !nullable) {
        throw new SQLException("required projection column is null at JDBC index " + index);
      }
      return value;
    }
  }

  private static final class ScalarMapping {

    private ScalarMapping() {}
  }
}
