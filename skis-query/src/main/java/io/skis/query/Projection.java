package io.skis.query;

import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.JdbcTypeCodec;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowDecoder;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.TableExpression;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, reflection-free mapping from one entity's persistent properties to a user result type.
 *
 * <p>SKIS loads user-type projection descriptors from APT-generated {@code *Projection} providers.
 * It resolves the generated JDBC codec for every selected property while compiling the query, binds
 * those properties to the caller's table expression, and reads result-set values by their one-based
 * column indexes.
 */
public final class Projection<E, R> {

  private static final Mapping<Object> SCALAR_MAPPING = new Mapping<>(ScalarMapping.class);

  private final Class<R> resultType;
  private final EntityMeta<E> entity;
  private final Mapping<R> mapping;
  private final List<PropertyMeta<E, ?>> properties;
  private final DecoderFactory<E, R> decoderFactory;
  private final @Nullable TableExpression<E> boundTable;
  private final boolean nullableResult;

  private Projection(
      Class<R> resultType,
      EntityMeta<E> entity,
      Mapping<R> mapping,
      List<? extends PropertyMeta<E, ?>> properties,
      DecoderFactory<E, R> decoderFactory,
      @Nullable TableExpression<E> boundTable,
      boolean nullableResult) {
    this.resultType = Objects.requireNonNull(resultType, "resultType");
    this.entity = Objects.requireNonNull(entity, "entity");
    this.mapping = Objects.requireNonNull(mapping, "mapping");
    Objects.requireNonNull(properties, "properties");
    if (properties.isEmpty()) {
      throw new QueryValidationException("a projection must select at least one column");
    }
    // noinspection ExtractMethodRecommender
    List<PropertyMeta<E, ?>> copy = new ArrayList<>(properties.size());
    for (PropertyMeta<E, ?> property : properties) {
      PropertyMeta<E, ?> selected = Objects.requireNonNull(property, "property");
      int ordinal = selected.ordinal();
      if (ordinal < 0
          || ordinal >= entity.properties().size()
          || entity.properties().get(ordinal) != selected) {
        throw new QueryValidationException(
            "projection property '"
                + selected.name()
                + "' does not belong to entity '"
                + entity.entityName()
                + "'");
      }
      copy.add(selected);
    }
    this.properties = List.copyOf(copy);
    this.decoderFactory = Objects.requireNonNull(decoderFactory, "decoderFactory");
    this.boundTable = boundTable;
    this.nullableResult = nullableResult;
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
   * <p>Application code should use {@link QueryOperations#selectProjection(QueryTable, Class)}
   * rather than calling this method directly.
   */
  public static <E, R> Projection<E, R> generated(
      Class<R> resultType,
      EntityMeta<E> entity,
      Mapping<R> mapping,
      List<? extends PropertyMeta<E, ?>> properties,
      DecoderFactory<E, R> decoderFactory) {
    return new Projection<>(resultType, entity, mapping, properties, decoderFactory, null, false);
  }

  static <E, V> Projection<E, V> scalar(NonNullQueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    return new Projection<E, V>(
        column.javaType(),
        column.expression().table().entity(),
        scalarMapping(),
        List.of(column.property()),
        readers -> {
          ValueReader<V> value = readers.reader(0, column.property());
          return (resultSet, context) -> requireResult(value.read(resultSet, context));
        },
        column.expression().table(),
        false);
  }

  static <E, V> Projection<E, V> nullableScalar(NullableQueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    return new Projection<E, V>(
        column.javaType(),
        column.expression().table().entity(),
        scalarMapping(),
        List.of(column.property()),
        readers -> {
          ValueReader<V> value = readers.reader(0, column.property());
          return value::read;
        },
        column.expression().table(),
        true);
  }

  /** Returns the generated user result type represented by this projection. */
  public Class<R> resultType() {
    return resultType;
  }

  /** Returns the canonical source entity metadata represented by this projection. */
  public EntityMeta<E> entity() {
    return entity;
  }

  Mapping<R> mapping() {
    return mapping;
  }

  List<PropertyMeta<E, ?>> properties() {
    return properties;
  }

  RowDecoder<R> rowDecoder(EntityRuntimeModel<E> model) {
    Objects.requireNonNull(model, "model");
    if (model.entity() != entity) {
      throw new QueryValidationException(
          "projection result type '"
              + resultType.getTypeName()
              + "' does not belong to runtime model '"
              + model.entity().entityName()
              + "'");
    }
    List<ProjectionValue<?>> values = new ArrayList<>(properties.size());
    for (int index = 0; index < properties.size(); index++) {
      PropertyMeta<E, ?> property = properties.get(index);
      int ordinal = property.ordinal();
      PropertyRuntime<?, ?> runtime = model.properties().get(ordinal);
      if (runtime.property() != property) {
        throw foreignProperty(model, property);
      }
      values.add(projectionValue(runtime.codec(), index + 1, property.column().nullable()));
    }
    Readers<E> readers = new CompiledReaders<>(properties, List.copyOf(values));
    RowDecoder<R> decoder =
        Objects.requireNonNull(decoderFactory.create(readers), "projection row decoder");
    return nullableResult
        ? decoder
        : (resultSet, context) -> requireResult(decoder.decode(resultSet, context));
  }

  void validateFrom(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    if (table.entity() != entity) {
      throw new QueryValidationException(
          "projection result type '"
              + resultType.getTypeName()
              + "' belongs to entity '"
              + entity.entityName()
              + "' but query table belongs to entity '"
              + table.entity().entityName()
              + "'");
    }
    if (boundTable != null && !boundTable.equals(table)) {
      throw new QueryValidationException(
          "scalar projection belongs to a different table expression than from");
    }
  }

  List<QueryColumn<E, ?>> columns(QueryTable<E> table) {
    validateFrom(table);
    List<QueryColumn<E, ?>> columns = new ArrayList<>(properties.size());
    for (PropertyMeta<E, ?> property : properties) {
      columns.add(queryColumn(table, property));
    }
    return List.copyOf(columns);
  }

  private static <E, V> QueryColumn<E, V> queryColumn(
      QueryTable<E> table, PropertyMeta<E, V> property) {
    return table.queryColumn(property);
  }

  private static QueryValidationException foreignProperty(
      EntityRuntimeModel<?> model, PropertyMeta<?, ?> property) {
    return new QueryValidationException(
        "projection property '"
            + property.name()
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
    return (Mapping<V>) SCALAR_MAPPING;
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
    <V> ValueReader<V> reader(int selectionIndex, PropertyMeta<E, V> property);
  }

  /** Reads one generated projection value without advancing the result-set cursor. */
  @FunctionalInterface
  public interface ValueReader<V> {
    @Nullable V read(ResultSet resultSet, RowReadContext context) throws SQLException;
  }

  private record CompiledReaders<E>(
      List<PropertyMeta<E, ?>> properties, List<ProjectionValue<?>> values) implements Readers<E> {

    @Override
    @SuppressWarnings("unchecked")
    public <V> ValueReader<V> reader(int selectionIndex, PropertyMeta<E, V> property) {
      Objects.requireNonNull(property, "property");
      if (selectionIndex < 0 || selectionIndex >= properties.size()) {
        throw new QueryValidationException(
            "projection selection index "
                + selectionIndex
                + " is outside [0, "
                + properties.size()
                + ")");
      }
      if (properties.get(selectionIndex) != property) {
        throw new QueryValidationException(
            "generated projection reader at index "
                + selectionIndex
                + " does not match property '"
                + property.name()
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
