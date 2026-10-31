package io.skis.mutation.internal;

import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisStatementKind;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.support.SqlCompiler;
import io.skis.dialect.support.SqlTemplate;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.jdbc.internal.JdbcFingerprint;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.mapping.spi.RowDecoder;
import io.skis.mutation.MutationPlanCacheStatistics;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.MutationQuerySupport.Inputs;
import io.skis.query.internal.MutationQuerySupport.Result;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.StatementAst;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Shared, bounded value-free plans; session bindings reuse this catalog without recompilation. */
final class DynamicMutationPlans {
  private static final int CAPACITY = 512;
  private final EntityRuntimeRegistry registry;
  private final Dialect dialect;
  private final ConcurrentHashMap<Key, Object> cache = new ConcurrentHashMap<>();
  private final Map<Key, Object> publicationOrder = new LinkedHashMap<>();
  private final Object maintenance = new Object();
  private final ReentrantLock[] compilationLocks = new ReentrantLock[32];
  private final LongAdder hitCount = new LongAdder();
  private final LongAdder missCount = new LongAdder();
  private final LongAdder evictionCount = new LongAdder();
  private final LongAdder invalidationCount = new LongAdder();
  private volatile long generation;

  /** Invocation-owned lookup: freezes structure once, never stores bound values in shared plans. */
  static final class Request<T> {
    private volatile @Nullable Lookup<T> lookup;

    private synchronized Lookup<T> initialize(Supplier<Lookup<T>> prepare) {
      Lookup<T> existing = lookup;
      if (existing == null) {
        existing = prepare.get();
        lookup = existing;
      }
      return existing;
    }
  }

  private record Lookup<T>(@Nullable Key key, Supplier<T> compile) {}

  private <T> T resolve(
      Request<T> request,
      @Nullable OperationObservation observation,
      String operation,
      Supplier<Lookup<T>> prepare) {
    Lookup<T> lookup = request.lookup;
    if (lookup == null) {
      lookup = request.initialize(prepare);
    }
    return cached(lookup.key(), observation, operation, lookup.compile());
  }

  DynamicMutationPlans(EntityRuntimeRegistry registry, Dialect dialect) {
    this.registry = registry;
    this.dialect = dialect;
    for (int index = 0; index < compilationLocks.length; index++) {
      compilationLocks[index] = new ReentrantLock();
    }
  }

  EntityRuntimeRegistry registry() {
    return registry;
  }

  int batchParameterLimit(int configured) {
    return switch (dialect.maxStatementParameters().kind()) {
      case EXPLICIT ->
          Math.min(configured, dialect.maxStatementParameters().maximum().orElseThrow());
      case UNKNOWN, UNBOUNDED -> configured;
    };
  }

  void validateBatchParameters(int count, int configured) {
    if (count > batchParameterLimit(configured))
      throw MutationException.of(
          SkisErrorCode.MUTATION_PARAMETER_SHAPE,
          "batch statement exceeds its final parameter budget");
  }

  boolean supportsGeneratedKeys() {
    return dialect.capabilities().supports(DialectFeature.JDBC_GENERATED_KEYS);
  }

  CompiledMutationPlan<@Nullable Object[]> count(
      Request<CompiledMutationPlan<@Nullable Object[]>> request,
      String operation,
      ResolvedStatement resolved,
      Inputs inputs,
      @Nullable OperationObservation observation) {
    Lookup<CompiledMutationPlan<@Nullable Object[]>> known = request.lookup;
    if (known != null) return cached(known.key(), observation, operation, known.compile());
    return resolve(
        request,
        observation,
        operation,
        () -> {
          Key key =
              inputs
                  .cacheIdentity()
                  .map(identity -> new Key(resolved.statement(), null, identity))
                  .orElse(null);
          List<JdbcTypeCodec<?>> codecs = inputs.codecs();
          return new Lookup<>(
              key,
              () -> {
                RenderedSql sql = render(resolved);
                return new CompiledMutationPlan<>(
                    operation, dialect.id(), sql, new ValuesBinder(sql.parameters(), codecs));
              });
        });
  }

  <R extends @Nullable Object> ConditionalPlan<R> conditional(
      Request<ConditionalPlan<R>> request,
      String operation,
      ResolvedStatement resolved,
      Inputs inputs,
      Result<R> result,
      @Nullable OperationObservation observation) {
    Lookup<ConditionalPlan<R>> known = request.lookup;
    if (known != null) return cached(known.key(), observation, operation, known.compile());
    return resolve(
        request,
        observation,
        operation,
        () -> {
          ResolvedStatement returning = resolved.returning(inputs.returningExpressions());
          return conditionalLookup(operation, returning, inputs, result);
        });
  }

  private <R extends @Nullable Object> Lookup<ConditionalPlan<R>> conditionalLookup(
      String operation,
      ResolvedStatement returning,
      MutationQuerySupport.Inputs inputs,
      MutationQuerySupport.Result<R> result) {
    Optional<?> identity = result.cacheIdentity(registry);
    Key key =
        identity.isPresent() && inputs.cacheIdentity().isPresent()
            ? new Key(returning.statement(), identity.get(), inputs.cacheIdentity().orElseThrow())
            : null;
    List<JdbcTypeCodec<?>> codecs = inputs.codecs();
    int columnCount = inputs.returningExpressions().size();
    return new Lookup<>(
        key,
        () -> {
          RenderedSql sql = render(returning);
          CompiledMutationPlan<@Nullable Object[]> plan =
              new CompiledMutationPlan<>(
                  operation, dialect.id(), sql, new ValuesBinder(sql.parameters(), codecs));
          RowDecoder<R> decoder = result.decoder(registry, inputs.returningExpressions());
          return new ConditionalPlan<>(plan, decoder, columnCount);
        });
  }

  private RenderedSql render(ResolvedStatement resolved) {
    return rendered(SqlCompiler.compile(resolved, dialect));
  }

  private RenderedSql rendered(SqlTemplate template) {
    int limit =
        switch (dialect.maxStatementParameters().kind()) {
          case EXPLICIT -> dialect.maxStatementParameters().maximum().orElseThrow();
          case UNKNOWN -> 1024;
          case UNBOUNDED -> Integer.MAX_VALUE;
        };
    if (template.parameterLayout().slots().size() > limit) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_PARAMETER_SHAPE,
          "mutation exceeds the single-statement parameter budget; automatic splitting is disabled");
    }
    return new RenderedSql(template.sql(), template.parameterLayout().slots());
  }

  private RenderedSql renderedBatch(SqlTemplate template, int budget) {
    validateBatchParameters(template.parameterLayout().size(), budget);
    return new RenderedSql(template.sql(), template.parameterLayout().slots());
  }

  /** Shares the existing bounded cache and only captures value-free compiler inputs. */
  <R extends @Nullable Object> StructuredPlan<R> structured(
      Request<StructuredPlan<R>> request,
      StructuredWrites.Prepared prepared,
      MutationQuerySupport.@Nullable Result<R> result,
      boolean readKey,
      @Nullable OperationObservation observation) {
    String operation = prepared.operation();
    Lookup<StructuredPlan<R>> known = request.lookup;
    if (known != null) return cached(known.key(), observation, operation, known.compile());
    return resolve(
        request,
        observation,
        operation,
        () -> {
          var inputs = prepared.inputs();
          ParameterBinder<?> prefix = prepared.prefix();
          int prefixCount = prepared.prefixCount();
          var entity = prepared.entityPlans();
          List<SqlExpression<?>> columns = new ArrayList<>();
          if (result != null) columns.addAll(inputs.returningExpressions());
          int keyIndex = -1;
          if (readKey) {
            var id = Objects.requireNonNull(entity).idProperty();
            for (int i = 0; i < columns.size(); i++)
              if (columns.get(i) instanceof ColumnExpression<?, ?> column
                  && column.property() == id) keyIndex = i;
            if (keyIndex < 0) {
              keyIndex = columns.size();
              columns.add(prepared.idColumn());
            }
          }
          ResolvedStatement resolved =
              columns.isEmpty() ? prepared.resolved() : prepared.resolved().returning(columns);
          StatementAst statement = resolved.statement();
          Optional<?> resultIdentity =
              result == null ? Optional.of(Boolean.FALSE) : result.cacheIdentity(registry);
          List<Object> identities = new ArrayList<>();
          inputs.cacheIdentity().ifPresent(identities::addAll);
          identities.add(prepared.prefixIdentity());
          identities.add(readKey);
          identities.add(prepared.checked());
          Key key =
              inputs.cacheIdentity().isPresent() && resultIdentity.isPresent()
                  ? new Key(statement, resultIdentity.get(), List.copyOf(identities))
                  : null;
          List<JdbcTypeCodec<?>> codecs = inputs.codecs();
          int idIndex = keyIndex + 1;
          JdbcTypeCodec<?> idCodec = readKey ? Objects.requireNonNull(entity).idCodec() : null;
          Class<?> idType =
              readKey ? Objects.requireNonNull(entity).idProperty().javaType() : Object.class;
          int outputCount = columns.size();
          return new Lookup<>(
              key,
              () -> {
                RenderedSql sql =
                    prepared.parameterBudget() == 0
                        ? render(resolved)
                        : renderedBatch(
                            SqlCompiler.compile(resolved, dialect), prepared.parameterBudget());
                CompiledMutationPlan<StructuredWrites.Arguments> compiled =
                    new CompiledMutationPlan<>(
                        operation,
                        dialect.id(),
                        sql,
                        StructuredBinder.create(prefix, prefixCount, sql.parameters(), codecs));
                if (outputCount == 0) return new StructuredPlan<>(compiled, null, 0, null);
                RowDecoder<R> values =
                    result == null ? null : result.decoder(registry, inputs.returningExpressions());
                RowDecoder<Returned<R>> decoder =
                    (rs, context) -> {
                      Object id = idCodec == null ? null : idCodec.read(rs, idIndex, context);
                      if (idCodec != null && !idType.isInstance(id))
                        throw MutationException.of(
                            SkisErrorCode.MUTATION_GENERATED_KEY_INVALID,
                            "structured RETURNING did not return the actual primary key");
                      @SuppressWarnings("unchecked")
                      R value =
                          values == null
                              ? (R) Objects.requireNonNull(id)
                              : values.decode(rs, context);
                      return new Returned<>(value, id);
                    };
                return new StructuredPlan<>(compiled, decoder, outputCount, values);
              });
        });
  }

  record StructuredPlan<R extends @Nullable Object>(
      CompiledMutationPlan<StructuredWrites.Arguments> statement,
      @Nullable RowDecoder<Returned<R>> decoder,
      int columnCount,
      @Nullable RowDecoder<R> outputDecoder) {}

  private record TailParameter(int offset, ParameterSlot<?> slot) {}

  private record StructuredBinder(
      @Nullable ParameterBinder<?> prefix,
      int prefixCount,
      int prefixOffset,
      int parameterCount,
      List<TailParameter> tail,
      List<JdbcTypeCodec<?>> codecs)
      implements ParameterBinder<StructuredWrites.Arguments> {

    static StructuredBinder create(
        @Nullable ParameterBinder<?> prefix,
        int prefixCount,
        List<ParameterSlot<?>> slots,
        List<JdbcTypeCodec<?>> codecs) {
      int offset = 0, seen = 0;
      List<TailParameter> tail = new ArrayList<>(slots.size() - prefixCount);
      for (int index = 0; index < slots.size(); index++) {
        ParameterSlot<?> slot = slots.get(index);
        if (slot.ordinal() < prefixCount) {
          if (seen == 0) offset = index;
          if (slot.ordinal() != seen || index != offset + seen)
            throw MutationException.of(
                SkisErrorCode.MUTATION_PARAMETER_SHAPE,
                "structured INSERT changed the generated binder segment");
          seen++;
        } else tail.add(new TailParameter(index, slot));
      }
      if (seen != prefixCount)
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "structured INSERT omitted generated binder inputs");
      return new StructuredBinder(
          prefix, prefixCount, offset, slots.size(), List.copyOf(tail), codecs);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public int bind(
        PreparedStatement statement,
        int firstIndex,
        StructuredWrites.Arguments arguments,
        JdbcWriteContext context)
        throws SQLException {
      if (prefix != null
          && ((ParameterBinder) prefix)
                  .bind(
                      statement,
                      firstIndex + prefixOffset,
                      Objects.requireNonNull(arguments.base()),
                      context)
              != firstIndex + prefixOffset + prefixCount)
        throw new SQLException("generated INSERT binder did not bind its declared segment");
      for (TailParameter position : tail) {
        int ordinal = position.slot().ordinal() - prefixCount;
        ValuesBinder.bindValue(
            codecs.get(ordinal),
            statement,
            firstIndex + position.offset(),
            arguments.values()[ordinal],
            context);
      }
      return firstIndex + parameterCount;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public int bindObserved(
        PreparedStatement statement,
        int firstIndex,
        StructuredWrites.Arguments arguments,
        JdbcWriteContext context,
        ParameterBindingObserver observer)
        throws SQLException {
      if (prefix != null
          && ((ParameterBinder) prefix)
                  .bindObserved(
                      statement,
                      firstIndex + prefixOffset,
                      Objects.requireNonNull(arguments.base()),
                      context,
                      observer)
              != firstIndex + prefixOffset + prefixCount)
        throw new SQLException("generated INSERT binder did not bind its declared segment");
      boolean observing = observer.needsMore();
      for (TailParameter position : tail) {
        ParameterSlot<?> slot = position.slot();
        int ordinal = slot.ordinal() - prefixCount, index = firstIndex + position.offset();
        Object value = arguments.values()[ordinal];
        ValuesBinder.bindValue(codecs.get(ordinal), statement, index, value, context);
        if (observing) {
          observer.bound(index, slot.javaType(), value);
          observing = observer.needsMore();
        }
      }
      return firstIndex + parameterCount;
    }
  }

  MutationPlanCacheStatistics statistics() {
    return new MutationPlanCacheStatistics(
        hitCount.sum(),
        missCount.sum(),
        evictionCount.sum(),
        invalidationCount.sum(),
        cache.size(),
        dialect.hasStablePlanCacheIdentity() ? CAPACITY : 0);
  }

  void clear() {
    synchronized (maintenance) {
      generation++;
      invalidationCount.add(cache.size());
      cache.clear();
      publicationOrder.clear();
    }
  }

  @SuppressWarnings("unchecked")
  private <T> T cached(
      @Nullable Key key,
      @Nullable OperationObservation observation,
      String operation,
      Supplier<T> compile) {
    if (key == null || !dialect.hasStablePlanCacheIdentity()) {
      return compile(compile, observation, operation);
    }
    Object existing = cache.get(key);
    if (existing != null) {
      hitCount.increment();
      return (T) existing;
    }
    missCount.increment();
    ReentrantLock lock =
        compilationLocks[(key.hashCode() & Integer.MAX_VALUE) % compilationLocks.length];
    boolean compilationStarted = false;
    long durationNanos = 0L;
    T candidate;
    try {
      lock.lock();
      try {
        long startedGeneration;
        synchronized (maintenance) {
          Object published = cache.get(key);
          if (published != null) {
            return (T) published;
          }
          startedGeneration = generation;
        }
        long startedNanos = observation == null ? 0L : System.nanoTime();
        compilationStarted = true;
        try {
          candidate = compile.get();
        } finally {
          if (observation != null) {
            durationNanos = Math.max(0L, System.nanoTime() - startedNanos);
          }
        }
        synchronized (maintenance) {
          if (startedGeneration == generation) {
            if (cache.size() == CAPACITY) {
              Key eldest = publicationOrder.keySet().iterator().next();
              publicationOrder.remove(eldest);
              cache.remove(eldest);
              evictionCount.increment();
            }
            cache.put(key, candidate);
            publicationOrder.put(key, candidate);
          }
        }
      } finally {
        lock.unlock();
      }
    } catch (RuntimeException | Error failure) {
      if (compilationStarted && observation != null) {
        notifyCompilation(observation, operation, durationNanos, null, failure);
      }
      throw failure;
    }
    if (observation != null) {
      notifyCompilation(observation, operation, durationNanos, candidate, null);
    }
    return candidate;
  }

  private static <T> T compile(
      Supplier<T> compiler, @Nullable OperationObservation observation, String operation) {
    if (observation == null) {
      return compiler.get();
    }
    long started = System.nanoTime();
    T completed;
    try {
      completed = compiler.get();
    } catch (RuntimeException | Error failure) {
      notifyCompilation(
          observation, operation, Math.max(0L, System.nanoTime() - started), null, failure);
      throw failure;
    }
    notifyCompilation(
        observation, operation, Math.max(0L, System.nanoTime() - started), completed, null);
    return completed;
  }

  private static void notifyCompilation(
      OperationObservation observation,
      String operation,
      long durationNanos,
      @Nullable Object completed,
      @Nullable Throwable failure) {
    CompiledMutationPlan<?> plan =
        switch (completed) {
          case null -> null;
          case CompiledMutationPlan<?> count -> count;
          case ConditionalPlan<?> conditional -> conditional.statement();
          case StructuredPlan<?> structured -> structured.statement();
          default -> throw new IllegalStateException("unknown mutation plan type");
        };
    observation.compilationCompleted(
        StatementPurpose.PRIMARY,
        SkisStatementKind.fromOperationName(operation),
        durationNanos,
        plan == null ? null : JdbcFingerprint.of(plan.sql()),
        plan == null ? null : plan.sql(),
        failure);
  }

  record ConditionalPlan<R extends @Nullable Object>(
      CompiledMutationPlan<@Nullable Object[]> statement, RowDecoder<R> decoder, int columnCount) {}

  record Returned<R extends @Nullable Object>(R value, @Nullable Object key) {
    @Override
    public String toString() {
      return "ReturnedRow[values redacted]";
    }
  }

  private static final class Key {
    private final List<Object> structure;
    private final @Nullable Object result;
    private final List<?> bindings;
    private final int hash;

    Key(StatementAst statement, @Nullable Object result, List<?> bindings) {
      this.structure = MutationStructureKey.of(statement);
      this.result = result;
      this.bindings = bindings;
      this.hash = Objects.hash(structure, result, bindings);
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return this == other
          || other instanceof Key key
              && hash == key.hash
              && structure.equals(key.structure)
              && Objects.equals(result, key.result)
              && bindings.equals(key.bindings);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  private record ValuesBinder(List<ParameterSlot<?>> positions, List<JdbcTypeCodec<?>> codecs)
      implements ParameterBinder<@Nullable Object[]> {
    private ValuesBinder(List<ParameterSlot<?>> positions, List<JdbcTypeCodec<?>> codecs) {
      this.positions = List.copyOf(positions);
      this.codecs = List.copyOf(codecs);
    }

    @Override
    public int bind(
        PreparedStatement statement,
        int firstIndex,
        @Nullable Object[] values,
        JdbcWriteContext context)
        throws SQLException {
      for (int offset = 0; offset < positions.size(); offset++) {
        ParameterSlot<?> slot = positions.get(offset);
        bindValue(
            codecs.get(slot.ordinal()),
            statement,
            firstIndex + offset,
            values[slot.ordinal()],
            context);
      }
      return firstIndex + positions.size();
    }

    @Override
    public int bindObserved(
        PreparedStatement statement,
        int firstIndex,
        @Nullable Object[] values,
        JdbcWriteContext context,
        ParameterBindingObserver observer)
        throws SQLException {
      boolean observing = observer.needsMore();
      for (int offset = 0; offset < positions.size(); offset++) {
        ParameterSlot<?> slot = positions.get(offset);
        Object value = values[slot.ordinal()];
        int index = firstIndex + offset;
        bindValue(codecs.get(slot.ordinal()), statement, index, value, context);
        if (observing) {
          observer.bound(index, slot.javaType(), value);
          observing = observer.needsMore();
        }
      }
      return firstIndex + positions.size();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bindValue(
        JdbcTypeCodec codec,
        PreparedStatement statement,
        int index,
        @Nullable Object value,
        JdbcWriteContext context)
        throws SQLException {
      codec.bind(statement, index, value, context);
    }
  }
}
