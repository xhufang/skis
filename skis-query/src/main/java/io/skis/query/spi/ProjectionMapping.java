package io.skis.query.spi;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.mapping.spi.RowDecoder;
import io.skis.mapping.spi.RowReadContext;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.query.ProjectionSelection;
import io.skis.query.Selectable;
import io.skis.sql.ast.Nullability;
import java.lang.reflect.Modifier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, query-independent constructor contract emitted by the SKIS annotation processor.
 *
 * <p>A mapping contains no table, expression, codec, alias, or runtime parameter value. Its decoder
 * factory is part of the executable result contract and therefore participates in shared-plan
 * identity. Generated reusable companions bind it through fixed-arity {@code of(...)} methods;
 * special companions expose it through {@code ProjectionType} for final-query-block source binding.
 */
public final class ProjectionMapping<R> {

  private static final AtomicLong NEXT_STATEFUL_DECODER_TOKEN = new AtomicLong();

  private final Class<R> resultType;
  private final String mappingId;
  private final List<Parameter> parameters;
  private final DecoderFactory<R> decoderFactory;
  private final long statefulDecoderToken;

  private ProjectionMapping(
      Class<R> resultType,
      String mappingId,
      List<? extends Parameter> parameters,
      DecoderFactory<R> decoderFactory) {
    this.resultType = Objects.requireNonNull(resultType, "resultType");
    this.mappingId = requireText(mappingId, "mappingId");
    Objects.requireNonNull(parameters, "parameters");
    if (parameters.isEmpty()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PROJECTION_MAPPING,
          "a projection mapping must declare at least one parameter");
    }
    List<Parameter> copy = new ArrayList<>(parameters.size());
    for (int ordinal = 0; ordinal < parameters.size(); ordinal++) {
      copy.add(requireParameter(parameters.get(ordinal), ordinal));
    }
    this.parameters = List.copyOf(copy);
    this.decoderFactory = Objects.requireNonNull(decoderFactory, "decoderFactory");
    this.statefulDecoderToken = statefulDecoderToken(decoderFactory);
  }

  private static Parameter requireParameter(Parameter parameter, int expectedOrdinal) {
    Objects.requireNonNull(parameter, "parameter");
    if (parameter.ordinal() != expectedOrdinal) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PROJECTION_MAPPING,
          "projection parameter ordinals must be dense from zero; expected "
              + expectedOrdinal
              + " but found "
              + parameter.ordinal());
    }
    if (parameter.constructorPosition() != expectedOrdinal) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PROJECTION_MAPPING,
          "projection parameter '"
              + parameter.name()
              + "' has constructor position "
              + parameter.constructorPosition()
              + " but expected "
              + expectedOrdinal);
    }
    return parameter;
  }

  /**
   * Infrastructure factory used only by APT-generated projection companions.
   *
   * <p>The ABI check deliberately runs during companion initialization so incompatible generated
   * sources fail with an actionable error before a query reaches JDBC. The factory must be
   * immutable for the lifetime of this mapping; factories carrying instance state receive a
   * mapping-instance identity so a caller-supplied {@code mappingId} cannot alias a different
   * executable decoder contract.
   */
  public static <R> ProjectionMapping<R> generated(
      int generatedAbi,
      Class<R> resultType,
      String mappingId,
      List<? extends Parameter> parameters,
      DecoderFactory<R> decoderFactory) {
    GeneratedModelAbi.requireCompatible(generatedAbi);
    return new ProjectionMapping<>(resultType, mappingId, parameters, decoderFactory);
  }

  /** Binds the generated constructor contract to one query's ordered selection expressions. */
  public ProjectionSelection<R> bind(Selectable<?>... selections) {
    Objects.requireNonNull(selections, "selections");
    return ProjectionSelection.generated(this, Arrays.asList(selections.clone()));
  }

  /** Returns the user result type constructed by this mapping. */
  public Class<R> resultType() {
    return resultType;
  }

  /** Returns the stable, value-independent generated mapping identity. */
  public String mappingId() {
    return mappingId;
  }

  /** Returns the constructor parameters in source order. */
  public List<Parameter> parameters() {
    return parameters;
  }

  /** Returns the generated decoder factory consumed by the query compiler. */
  public DecoderFactory<R> decoderFactory() {
    return decoderFactory;
  }

  /** Returns the executable decoder-factory type used for stable plan identity. */
  public Class<?> decoderFactoryType() {
    return decoderFactory.getClass();
  }

  /** Returns zero for stateless factories or this mapping's process-local stateful identity. */
  public long statefulDecoderToken() {
    return statefulDecoderToken;
  }

  private static long statefulDecoderToken(DecoderFactory<?> decoderFactory) {
    if (!hasInstanceState(decoderFactory.getClass())) {
      return 0;
    }
    long token = NEXT_STATEFUL_DECODER_TOKEN.incrementAndGet();
    if (token <= 0) {
      throw new IllegalStateException("projection decoder identity space is exhausted");
    }
    return token;
  }

  private static boolean hasInstanceState(Class<?> factoryType) {
    for (Class<?> current = factoryType; current != null; current = current.getSuperclass()) {
      if (Arrays.stream(current.getDeclaredFields())
          .anyMatch(field -> !Modifier.isStatic(field.getModifiers()))) {
        return true;
      }
    }
    return false;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /** Stable generated contract for one constructor parameter. */
  public record Parameter(
      int ordinal,
      String name,
      Class<?> javaType,
      Nullability nullability,
      int constructorPosition) {

    public Parameter {
      if (ordinal < 0) {
        throw new IllegalArgumentException("projection parameter ordinal must not be negative");
      }
      name = requireText(name, "name");
      javaType = boxed(Objects.requireNonNull(javaType, "javaType"));
      if (javaType == Void.class) {
        throw new IllegalArgumentException("projection parameter Java type must not be void");
      }
      Objects.requireNonNull(nullability, "nullability");
      if (constructorPosition < 0) {
        throw new IllegalArgumentException("projection constructor position must not be negative");
      }
    }

    /** Whether the user constructor parameter can receive SQL {@code NULL}. */
    public boolean acceptsNoNull() {
      return !nullability.isNullable();
    }
  }

  /** Builds one row decoder after the query compiler has resolved every selection reader. */
  @FunctionalInterface
  public interface DecoderFactory<R> {
    RowDecoder<R> create(Readers readers);
  }

  /** Typed reader lookup supplied once while a generated projection decoder is assembled. */
  public interface Readers {

    /**
     * Resolves the reader for one zero-based constructor parameter. Generated code supplies the
     * parameter's complete Java type, including its nullable type argument. A non-null parameter's
     * compiled reader rejects SQL NULL before returning; no second row-time check is required.
     */
    <V extends @Nullable Object> ValueReader<V> reader(int parameterOrdinal, Class<?> javaType);
  }

  /** Reads one resolved selection; {@code V} carries the generated parameter's nullability. */
  @FunctionalInterface
  public interface ValueReader<V extends @Nullable Object> {
    V read(ResultSet resultSet, RowReadContext context) throws SQLException;
  }

  private static Class<?> boxed(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    return switch (type.getName()) {
      case "boolean" -> Boolean.class;
      case "byte" -> Byte.class;
      case "short" -> Short.class;
      case "int" -> Integer.class;
      case "long" -> Long.class;
      case "float" -> Float.class;
      case "double" -> Double.class;
      case "char" -> Character.class;
      case "void" -> Void.class;
      default -> throw new IllegalArgumentException("unsupported primitive Java type " + type);
    };
  }
}
