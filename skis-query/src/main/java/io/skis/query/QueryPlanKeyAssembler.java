package io.skis.query;

import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.sql.ast.QueryBlockAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** One authority for deciding whether a fully validated statement can enter shared L1. */
final class QueryPlanKeyAssembler {

  private final EntityRuntimeRegistry registry;
  private final QueryPlanKey.IdentityScope identities;
  private final Optional<QueryPlanKey.DialectIdentity> dialect;
  private final boolean enabled;

  QueryPlanKeyAssembler(
      EntityRuntimeRegistry registry,
      QueryPlanKey.IdentityScope identities,
      Optional<QueryPlanKey.DialectIdentity> dialect,
      boolean enabled) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.identities = Objects.requireNonNull(identities, "identities");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.enabled = enabled;
  }

  Decision assemble(
      QueryBlockAnalysis analysis,
      SelectedResult<?> selected,
      QueryPlanKey.PlanVariant variant,
      List<QueryPlanCompiler.LogicalParameter> parameters,
      List<@Nullable Selectable<?>> sources) {
    if (!enabled) {
      return Bypass.CACHE_DISABLED;
    }
    if (dialect.isEmpty()) {
      return Bypass.UNSTABLE_DIALECT;
    }
    QueryPlanDependencies dependencies = QueryPlanDependencies.from(analysis);
    for (var entity : dependencies.entities()) {
      if (registry.find(entity).isEmpty()) {
        return Bypass.UNREGISTERED_DEPENDENCY;
      }
    }
    Optional<QueryPlanKey.ResultShape> result = selected.planKeyResultShape(identities);
    if (result.isEmpty()) {
      return Bypass.UNSAFE_RESULT_IDENTITY;
    }
    if (parameters.size() != sources.size()) {
      throw new IllegalArgumentException("parameter descriptors and Codec sources differ");
    }
    List<QueryPlanKey.ParameterShape> shapes = new ArrayList<>(parameters.size());
    for (int index = 0; index < parameters.size(); index++) {
      QueryPlanCompiler.LogicalParameter parameter = parameters.get(index);
      Optional<QueryPlanKey.ParameterShape> shape =
          switch (parameter.scalarBinding()) {
            case INTEGER ->
                Optional.of(QueryPlanKey.ParameterShape.paginationInteger(index));
            case LONG -> Optional.of(QueryPlanKey.ParameterShape.paginationLong(index));
            case NONE ->
                QueryPlanKey.ParameterShape.codec(
                    identities,
                    parameter.descriptor(),
                    Objects.requireNonNull(sources.get(index), "Codec source"));
          };
      if (shape.isEmpty()) {
        return Bypass.UNSAFE_PARAMETER_IDENTITY;
      }
      shapes.add(shape.orElseThrow());
    }
    return new Cacheable(
        new QueryPlanKey(
            analysis.structureKey(),
            QueryPlanKey.Dependencies.from(dependencies),
            result.orElseThrow(),
            variant,
            shapes,
            dialect.orElseThrow(),
            Map.of()),
        dependencies);
  }

  sealed interface Decision permits Cacheable, Bypass {}

  record Cacheable(QueryPlanKey key, QueryPlanDependencies dependencies) implements Decision {
    Cacheable {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(dependencies, "dependencies");
    }
  }

  enum Bypass implements Decision {
    CACHE_DISABLED,
    UNSTABLE_DIALECT,
    UNREGISTERED_DEPENDENCY,
    UNSAFE_RESULT_IDENTITY,
    UNSAFE_PARAMETER_IDENTITY
  }
}
