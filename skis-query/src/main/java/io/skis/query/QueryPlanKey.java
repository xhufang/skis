package io.skis.query;

import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.metadata.EntityMeta;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.ResolvedStructureKey;
import io.skis.sql.ast.SqlType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable, value-independent identity of one shareable compiled query plan.
 *
 * <p>The key is scoped to one {@link QueryPlanCatalog}. It deliberately stores structural value
 * tokens, strings, and enums instead of runtime values, metadata objects, classes, codecs,
 * connections, sessions, or other execution resources. The composite hash is calculated once
 * because shared-cache lookups will read it on every execution after T05/T06 wire the L1 cache.
 */
final class QueryPlanKey {

  private static final AtomicLong NEXT_RUNTIME_TYPE_TOKEN = new AtomicLong();
  private static final ClassValue<RuntimeTypeIdentity> RUNTIME_TYPE_IDENTITIES =
      new ClassValue<>() {
        @Override
        protected RuntimeTypeIdentity computeValue(Class<?> type) {
          long token = NEXT_RUNTIME_TYPE_TOKEN.incrementAndGet();
          if (token <= 0) {
            throw new IllegalStateException("runtime type identity space is exhausted");
          }
          return new RuntimeTypeIdentity(token, type.getName());
        }
      };

  private final ResolvedStructureKey structure;
  private final ResultShape resultShape;
  private final PlanVariant variant;
  private final List<ParameterShape> parameters;
  private final DialectIdentity dialect;
  private final Map<String, String> structuralContextSignatures;
  private final int hashCode;

  QueryPlanKey(
      ResolvedStructureKey structure,
      ResultShape resultShape,
      PlanVariant variant,
      List<? extends ParameterShape> parameters,
      DialectIdentity dialect,
      Map<String, String> structuralContextSignatures) {
    this.structure = Objects.requireNonNull(structure, "structure");
    this.resultShape = Objects.requireNonNull(resultShape, "resultShape");
    this.variant = Objects.requireNonNull(variant, "variant");
    this.parameters = copyParameters(parameters);
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.structuralContextSignatures = copySignatures(structuralContextSignatures);
    this.hashCode = calculateHashCode();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof QueryPlanKey key
            && hashCode == key.hashCode
            && structure.equals(key.structure)
            && resultShape.equals(key.resultShape)
            && variant.equals(key.variant)
            && parameters.equals(key.parameters)
            && dialect.equals(key.dialect)
            && structuralContextSignatures.equals(key.structuralContextSignatures);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public String toString() {
    return "QueryPlanKey[structure="
        + structure.canonicalForm()
        + ", resultShape="
        + resultShape
        + ", variant="
        + variant
        + ", parameters="
        + parameters
        + ", dialect="
        + dialect
        + ", structuralContextSignatures="
        + structuralContextSignatures
        + ']';
  }

  private int calculateHashCode() {
    int result = structure.hashCode();
    result = 31 * result + resultShape.hashCode();
    result = 31 * result + variant.hashCode();
    result = 31 * result + parameters.hashCode();
    result = 31 * result + dialect.hashCode();
    return 31 * result + structuralContextSignatures.hashCode();
  }

  private static List<ParameterShape> copyParameters(
      List<? extends ParameterShape> parameters) {
    Objects.requireNonNull(parameters, "parameters");
    List<ParameterShape> copy = List.copyOf(parameters);
    for (int ordinal = 0; ordinal < copy.size(); ordinal++) {
      ParameterShape parameter = Objects.requireNonNull(copy.get(ordinal), "parameter");
      if (parameter.ordinal() != ordinal) {
        throw new IllegalArgumentException(
            "query plan parameter ordinals must be dense from zero; expected "
                + ordinal
                + " but found "
                + parameter.ordinal());
      }
    }
    return copy;
  }

  private static Map<String, String> copySignatures(Map<String, String> signatures) {
    Objects.requireNonNull(signatures, "structuralContextSignatures");
    TreeMap<String, String> copy = new TreeMap<>();
    signatures.forEach(
        (name, signature) ->
            copy.put(
                requireText(name, "structural context name"),
                requireText(signature, "structural context signature")));
    return Collections.unmodifiableMap(copy);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String stableIdentity(String... components) {
    Objects.requireNonNull(components, "components");
    StringBuilder identity = new StringBuilder();
    for (int index = 0; index < components.length; index++) {
      String component = requireText(components[index], "identity component #" + index);
      identity.append(component.length()).append(':').append(component);
    }
    return identity.toString();
  }

  private static RuntimeTypeIdentity runtimeTypeIdentity(Class<?> type) {
    return RUNTIME_TYPE_IDENTITIES.get(Objects.requireNonNull(type, "type"));
  }

  /** Catalog-bound authority for converting canonical entity metadata into value-only identities. */
  static final class IdentityScope {

    private final EntityRuntimeRegistry runtimeRegistry;

    IdentityScope(EntityRuntimeRegistry runtimeRegistry) {
      this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    }

    private Optional<RuntimeTypeIdentity> registeredEntity(EntityMeta<?> entity) {
      EntityMeta<?> candidate = Objects.requireNonNull(entity, "entity");
      if (runtimeRegistry.find(candidate).isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(runtimeTypeIdentity(candidate.javaType()));
    }
  }

  /** Loader-aware process-local type token that does not retain its originating {@link Class}. */
  private record RuntimeTypeIdentity(long token, String binaryName) {

    private RuntimeTypeIdentity {
      if (token <= 0) {
        throw new IllegalArgumentException("runtime type token must be positive");
      }
      binaryName = requireText(binaryName, "runtime type binaryName");
    }
  }

  /**
   * Stable result-construction contract; selected expressions remain in the enclosing key's
   * {@code ResolvedStructureKey}.
   */
  record ResultShape(
      Kind kind,
      String stableId,
      RuntimeTypeIdentity resultType,
      List<BindingIdentity> selectionBindings) {

    ResultShape {
      Objects.requireNonNull(kind, "kind");
      stableId = requireText(stableId, "result shape stableId");
      Objects.requireNonNull(resultType, "resultType");
      selectionBindings = List.copyOf(selectionBindings);
      selectionBindings.forEach(
          binding -> Objects.requireNonNull(binding, "result selection binding"));
      switch (kind) {
        case REQUIRED_ENTITY, NULLABLE_ENTITY -> {
          if (!selectionBindings.isEmpty()) {
            throw new IllegalArgumentException(
                "entity result shapes must not carry selection bindings");
          }
        }
        case REQUIRED_SCALAR, NULLABLE_SCALAR -> {
          if (selectionBindings.size() != 1) {
            throw new IllegalArgumentException(
                "scalar result shapes require exactly one selection binding");
          }
        }
        case GENERATED_PROJECTION -> {
          if (selectionBindings.isEmpty()) {
            throw new IllegalArgumentException(
                "projection result shapes require at least one selection binding");
          }
        }
      }
    }

    static Optional<ResultShape> requiredEntity(IdentityScope identities, QueryTable<?> table) {
      return entity(identities, table, Kind.REQUIRED_ENTITY);
    }

    static Optional<ResultShape> nullableEntity(IdentityScope identities, QueryTable<?> table) {
      return entity(identities, table, Kind.NULLABLE_ENTITY);
    }

    static Optional<ResultShape> requiredScalar(
        IdentityScope identities, Selectable<?> selectable) {
      return scalar(identities, selectable, Kind.REQUIRED_SCALAR);
    }

    static Optional<ResultShape> nullableScalar(
        IdentityScope identities, Selectable<?> selectable) {
      return scalar(identities, selectable, Kind.NULLABLE_SCALAR);
    }

    static Optional<ResultShape> projection(
        IdentityScope identities, ProjectionSelection<?> selection) {
      Objects.requireNonNull(identities, "identities");
      ProjectionSelection<?> selected = Objects.requireNonNull(selection, "selection");
      List<BindingIdentity> bindings = new ArrayList<>(selected.selections().size());
      for (Selectable<?> selectable : selected.selections()) {
        Optional<BindingIdentity> binding = codecBinding(identities, selectable);
        if (binding.isEmpty()) {
          return Optional.empty();
        }
        bindings.add(binding.orElseThrow());
      }
      return Optional.of(
          new ResultShape(
              Kind.GENERATED_PROJECTION,
              stableIdentity(selected.resultType().getName(), selected.mappingId()),
              runtimeTypeIdentity(selected.resultType()),
              bindings));
    }

    private static Optional<ResultShape> entity(
        IdentityScope identities, QueryTable<?> table, Kind kind) {
      Objects.requireNonNull(identities, "identities");
      QueryTable<?> selected = Objects.requireNonNull(table, "table");
      return identities
          .registeredEntity(selected.entity())
          .map(
              entityType ->
                  new ResultShape(
                      kind,
                      selected.entity().javaType().getName(),
                      entityType,
                      List.of()));
    }

    private static Optional<ResultShape> scalar(
        IdentityScope identities, Selectable<?> selectable, Kind kind) {
      Objects.requireNonNull(identities, "identities");
      Selectable<?> selected = Objects.requireNonNull(selectable, "selectable");
      return codecBinding(identities, selected)
          .map(
              binding ->
                  new ResultShape(
                      kind,
                      stableIdentity(selected.javaType().getName(), selected.sqlType().name()),
                      runtimeTypeIdentity(selected.javaType()),
                      List.of(binding)));
    }

    private enum Kind {
      REQUIRED_ENTITY,
      NULLABLE_ENTITY,
      REQUIRED_SCALAR,
      NULLABLE_SCALAR,
      GENERATED_PROJECTION
    }
  }

  /** Statement/decoder variant that cannot share a plan with another terminal operation. */
  record PlanVariant(ResultMode resultMode, QueryPaginationShape pagination) {

    PlanVariant {
      Objects.requireNonNull(resultMode, "resultMode");
      Objects.requireNonNull(pagination, "pagination");
      if (resultMode == ResultMode.COUNT
          && pagination.mode() != QueryPaginationShape.Mode.NONE) {
        throw new IllegalArgumentException("a count plan must not carry content pagination");
      }
    }

    static PlanVariant content(QueryPaginationShape pagination) {
      return new PlanVariant(ResultMode.CONTENT, pagination);
    }

    static PlanVariant orderedContent(QueryPaginationShape pagination) {
      return new PlanVariant(ResultMode.ORDERED_CONTENT, pagination);
    }

    static PlanVariant count() {
      return new PlanVariant(ResultMode.COUNT, QueryPaginationShape.none());
    }

    private enum ResultMode {
      CONTENT,
      ORDERED_CONTENT,
      COUNT
    }
  }

  /** Final logical parameter descriptor and stable binder selection, in dense slot order. */
  record ParameterShape(
      int ordinal,
      String javaTypeName,
      SqlType sqlType,
      Nullability nullability,
      BindingIdentity binding) {

    ParameterShape {
      if (ordinal < 0) {
        throw new IllegalArgumentException("parameter ordinal must not be negative");
      }
      javaTypeName = requireText(javaTypeName, "parameter javaTypeName");
      Objects.requireNonNull(sqlType, "sqlType");
      Objects.requireNonNull(nullability, "nullability");
      Objects.requireNonNull(binding, "binding");
    }

    /** Returns empty when the Codec source has no stable identity and L1 must be bypassed. */
    static Optional<ParameterShape> codec(
        IdentityScope identities, ParameterSlot<?> descriptor, Selectable<?> source) {
      Objects.requireNonNull(identities, "identities");
      ParameterSlot<?> slot = Objects.requireNonNull(descriptor, "descriptor");
      Selectable<?> selectable = Objects.requireNonNull(source, "source");
      if (!slot.javaType().equals(selectable.javaType())
          || slot.sqlType() != selectable.sqlType()) {
        throw new IllegalArgumentException(
            "parameter descriptor must match its selectable Codec source");
      }
      return codecBinding(identities, selectable)
          .map(
              binding ->
                  new ParameterShape(
                      slot.ordinal(),
                      slot.javaType().getName(),
                      slot.sqlType(),
                      slot.nullability(),
                      binding));
    }

    static ParameterShape paginationInteger(int ordinal) {
      return new ParameterShape(
          ordinal,
          Integer.class.getName(),
          SqlType.INTEGER,
          Nullability.NON_NULL,
          PaginationBindingIdentity.INTEGER);
    }

    static ParameterShape paginationLong(int ordinal) {
      return new ParameterShape(
          ordinal,
          Long.class.getName(),
          SqlType.BIGINT,
          Nullability.NON_NULL,
          PaginationBindingIdentity.LONG);
    }
  }

  /** Stable binder contract; no {@code JdbcTypeCodec}, metadata, or {@link Class} is retained. */
  private sealed interface BindingIdentity
      permits CodecBindingIdentity, PaginationBindingIdentity {}

  private record CodecBindingIdentity(RuntimeTypeIdentity entityType, int propertyOrdinal)
      implements BindingIdentity {

    private CodecBindingIdentity {
      Objects.requireNonNull(entityType, "entityType");
      if (propertyOrdinal < 0) {
        throw new IllegalArgumentException("property ordinal must not be negative");
      }
    }
  }

  private enum PaginationBindingIdentity implements BindingIdentity {
    INTEGER,
    LONG
  }

  private static Optional<BindingIdentity> codecBinding(
      IdentityScope identities, Selectable<?> source) {
    Selectable<?> selectable = Objects.requireNonNull(source, "source");
    return switch (selectable) {
      case QueryColumn<?, ?> column -> propertyBinding(identities, column);
      case DerivedColumnSelectable<?> derived ->
          codecBinding(identities, derived.output().selectable());
      case ScalarSubquerySelectable<?> scalar -> codecBinding(identities, scalar.output());
      default -> Optional.empty();
    };
  }

  private static Optional<BindingIdentity> propertyBinding(
      IdentityScope identities, QueryColumn<?, ?> column) {
    Objects.requireNonNull(identities, "identities");
    int ordinal = column.property().ordinal();
    var entity = column.table().entity();
    if (ordinal < 0
        || ordinal >= entity.properties().size()
        || entity.properties().get(ordinal) != column.property()) {
      throw new IllegalArgumentException(
          "query column does not use its entity's canonical property metadata");
    }
    return identities
        .registeredEntity(entity)
        .map(entityType -> new CodecBindingIdentity(entityType, ordinal));
  }

  /** Dialect plan identity; T04 will source the capability version from the Dialect contract. */
  record DialectIdentity(String dialectId, int capabilityVersion) {

    DialectIdentity {
      dialectId = requireText(dialectId, "dialectId");
      if (!dialectId.equals(dialectId.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("dialectId must be lowercase");
      }
    }
  }
}
