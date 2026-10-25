package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.QueryParameter;
import io.skis.query.Selectable;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Assigns final statement-level logical slots while keeping reuse local to one query-block
 * occurrence.
 *
 * <p>Nested-query traversal can create a fresh {@link QueryBlock} for every occurrence while
 * sharing this statement layout. Reusing one parameter inside a block then reuses its logical slot,
 * while embedding the same block twice can allocate distinct statement slots backed by the same
 * query-level reference. Identical repeated DISTINCT SELECT outputs share their compiled expression
 * and slots; every output occurrence still undergoes complete scope and semantic analysis.
 *
 * <p>The first pass retains original expressions for validation. When it finds an empty-IN operand
 * or DISTINCT subquery-containing ordering that can reuse a selected output, a second pass lays out
 * only the parameters needed by the final statement.
 */
final class StatementParameterLayout {

  private final List<Selectable<?>> parameterSources = new ArrayList<>();
  private final List<QueryParameter<?>> parameterReferences = new ArrayList<>();
  private final List<ParameterSlot<?>> parameterSlots = new ArrayList<>();
  private @Nullable List<Selectable<?>> builtinParameterSources;
  private @Nullable IdentityHashMap<Object, Boolean> builtinSourceIdentities;
  private final boolean retainOriginalExpressions;
  private boolean rewriteRequired;

  StatementParameterLayout() {
    this(true);
  }

  StatementParameterLayout(boolean retainOriginalExpressions) {
    this.retainOriginalExpressions = retainOriginalExpressions;
  }

  boolean retainOriginalExpressions() {
    return retainOriginalExpressions;
  }

  void recordEmptyMembership() {
    rewriteRequired = true;
  }

  void recordSelectedOrdering() {
    rewriteRequired = true;
  }

  boolean requiresRewrite() {
    return rewriteRequired;
  }

  QueryBlock newQueryBlock() {
    return new QueryBlock();
  }

  List<Selectable<?>> parameterSources() {
    return List.copyOf(parameterSources);
  }

  List<QueryParameter<?>> parameterReferences() {
    return List.copyOf(parameterReferences);
  }

  List<ParameterSlot<?>> parameterSlots() {
    return List.copyOf(parameterSlots);
  }

  List<Selectable<?>> builtinParameterSources() {
    return builtinParameterSources == null ? List.of() : List.copyOf(builtinParameterSources);
  }

  private void requireBuiltinSource(Selectable<?> source) {
    if (builtinSourceIdentities == null) {
      builtinSourceIdentities = new IdentityHashMap<>();
      builtinParameterSources = new ArrayList<>();
    }
    Object identity =
        source instanceof ExpressionSelectable<?> expression ? expression.codecIdentity() : source;
    if (builtinSourceIdentities.put(identity, Boolean.TRUE) == null) {
      Objects.requireNonNull(builtinParameterSources).add(source);
    }
  }

  final class QueryBlock {

    private final IdentityHashMap<QueryParameter<?>, RegisteredParameter> parameters =
        new IdentityHashMap<>();

    <V> ParameterSlot<V> parameter(Selectable<V> source, QueryParameter<V> parameter) {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(parameter, "parameter");
      if (!source.javaType().equals(parameter.javaType())) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_TYPE_MISMATCH,
            "query parameter Java type "
                + parameter.javaType().getTypeName()
                + " does not match expression '"
                + SelectableSupport.summary(source)
                + "' type "
                + source.javaType().getTypeName());
      }
      RegisteredParameter registered = parameters.get(parameter);
      if (registered != null) {
        ParameterSlot<?> existing = registered.slot();
        if (!existing.javaType().equals(source.javaType())
            || existing.sqlType() != source.sqlType()
            || existing.nullability() != parameter.nullability()) {
          throw QueryValidationException.of(
              SkisErrorCode.QUERY_PARAMETER_LAYOUT,
              "query parameter is used with conflicting Java type, SQL type or nullability");
        }
        boolean registeredStandard = registered.binderSource() instanceof ExpressionSelectable<?>;
        boolean sourceStandard = source instanceof ExpressionSelectable<?>;
        // A reused slot keeps one binder, but every discarded standard source still has to
        // prove its value provenance. In particular, a prior value(p) cannot hide a custom input.
        if (sourceStandard && source != registered.binderSource()) {
          requireBuiltinSource(source);
        }
        if (registeredStandard != sourceStandard) {
          Selectable<?> mappedSource = registeredStandard ? source : registered.binderSource();
          if (mappedSource.sqlType() == SqlType.OTHER
              || SqlType.fromJavaType(mappedSource.javaType()) != mappedSource.sqlType()) {
            throw conflictingSources(registered.binderSource(), source);
          }
          if (!registered.requiresBuiltinCodec()) {
            if (registeredStandard) {
              requireBuiltinSource(registered.binderSource());
            }
            requireBuiltinSource(mappedSource);
            // Prefer the actual mapped source in either encounter order. Its built-in Codec
            // identity is checked before plan lookup, including sources later pruned from SQL.
            parameterSources.set(existing.ordinal(), mappedSource);
            parameters.put(parameter, new RegisteredParameter(existing, mappedSource, true));
          }
        } else if (!registeredStandard
            && !SelectableSupport.sameOccurrence(registered.binderSource(), source)) {
          throw conflictingSources(registered.binderSource(), source);
        }
        @SuppressWarnings("unchecked")
        ParameterSlot<V> typed = (ParameterSlot<V>) existing;
        return typed;
      }
      int ordinal = parameterReferences.size();
      ParameterSlot<V> slot =
          new ParameterSlot<>(
              ordinal, source.javaType(), source.sqlType(), parameter.nullability());
      parameters.put(parameter, new RegisteredParameter(slot, source, false));
      parameterSources.add(source);
      parameterReferences.add(parameter);
      parameterSlots.add(slot);
      return slot;
    }
  }

  private static QueryValidationException conflictingSources(
      Selectable<?> left, Selectable<?> right) {
    return QueryValidationException.of(
        SkisErrorCode.QUERY_PARAMETER_LAYOUT,
        "query parameter is reused with conflicting expression Codec sources '"
            + SelectableSupport.summary(left)
            + "' and '"
            + SelectableSupport.summary(right)
            + "'");
  }

  private record RegisteredParameter(
      ParameterSlot<?> slot, Selectable<?> binderSource, boolean requiresBuiltinCodec) {

    private RegisteredParameter {
      Objects.requireNonNull(slot, "slot");
      Objects.requireNonNull(binderSource, "binderSource");
    }
  }
}
