package io.skis.query;

import io.skis.mapping.RowDecoder;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.sql.ast.Nullability;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, query-independent constructor contract emitted by the SKIS annotation processor.
 *
 * <p>A mapping contains no table, expression, codec, alias, or runtime parameter value. Generated
 * companion classes keep their mapping private and expose fixed-arity {@code of(...)} methods that
 * bind it to one query's selections.
 */
public final class ProjectionMapping<R> {

  private final Class<R> resultType;
  private final String mappingId;
  private final List<Parameter> parameters;
  private final DecoderFactory<R> decoderFactory;

  private ProjectionMapping(
      Class<R> resultType,
      String mappingId,
      List<? extends Parameter> parameters,
      DecoderFactory<R> decoderFactory) {
    this.resultType = Objects.requireNonNull(resultType, "resultType");
    this.mappingId = requireText(mappingId, "mappingId");
    Objects.requireNonNull(parameters, "parameters");
    if (parameters.isEmpty()) {
      throw new QueryValidationException(
          "a projection mapping must declare at least one parameter");
    }
    List<Parameter> copy = new ArrayList<>(parameters.size());
    for (int ordinal = 0; ordinal < parameters.size(); ordinal++) {
      Parameter parameter = Objects.requireNonNull(parameters.get(ordinal), "parameter");
      if (parameter.ordinal() != ordinal) {
        throw new QueryValidationException(
            "projection parameter ordinals must be dense from zero; expected "
                + ordinal
                + " but found "
                + parameter.ordinal());
      }
      if (parameter.constructorPosition() != ordinal) {
        throw new QueryValidationException(
            "projection parameter '"
                + parameter.name()
                + "' has constructor position "
                + parameter.constructorPosition()
                + " but expected "
                + ordinal);
      }
      copy.add(parameter);
    }
    this.parameters = List.copyOf(copy);
    this.decoderFactory = Objects.requireNonNull(decoderFactory, "decoderFactory");
  }

  /**
   * Infrastructure factory used only by APT-generated projection companions.
   *
   * <p>The ABI check deliberately runs during companion initialization so incompatible generated
   * sources fail with an actionable error before a query reaches JDBC.
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
    return new ProjectionSelection<>(this, Arrays.asList(selections.clone()));
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

  DecoderFactory<R> decoderFactory() {
    return decoderFactory;
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

    /** Resolves the reader for one zero-based constructor parameter. */
    <V> ValueReader<V> reader(int parameterOrdinal, Class<?> javaType);
  }

  /** Reads one resolved selection without advancing the result-set cursor. */
  @FunctionalInterface
  public interface ValueReader<V> {
    @Nullable V read(ResultSet resultSet, RowReadContext context) throws SQLException;
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
