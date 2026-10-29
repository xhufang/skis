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
import io.skis.mapping.spi.InsertArguments;
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
import io.skis.sql.ast.ReturningStatement;
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

  static final class InsertRequest<E, R> {
    final Request<Plan<R, E>> assigned = new Request<>();
    final Request<Plan<R, InsertArguments<E>>> generated = new Request<>();
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

  boolean supportsGeneratedKeys() {
    return dialect.capabilities().supports(DialectFeature.JDBC_GENERATED_KEYS);
  }

  CompiledMutationPlan<@Nullable Object[]> count(
      Request<CompiledMutationPlan<@Nullable Object[]>> request,
      String operation,
      StatementAst statement,
      MutationQuerySupport.Inputs inputs,
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
                  .map(identity -> new Key(statement, null, identity))
                  .orElse(null);
          List<JdbcTypeCodec<?>> codecs = inputs.codecs();
          return new Lookup<>(
              key,
              () -> {
                RenderedSql sql = render(statement);
                return new CompiledMutationPlan<>(
                    operation, dialect.id(), sql, new ValuesBinder(sql.parameters(), codecs));
              });
        });
  }

  <R> ConditionalPlan<R> conditional(
      Request<ConditionalPlan<R>> request,
      String operation,
      StatementAst statement,
      MutationQuerySupport.Inputs inputs,
      MutationQuerySupport.Result<R> result,
      @Nullable OperationObservation observation) {
    Lookup<ConditionalPlan<R>> known = request.lookup;
    if (known != null) return cached(known.key(), observation, operation, known.compile());
    return resolve(
        request,
        observation,
        operation,
        () ->
            conditionalLookup(
                operation,
                new ReturningStatement(statement, result.columns()),
                null,
                inputs,
                result));
  }

  <R> ConditionalPlan<R> conditional(
      Request<ConditionalPlan<R>> request,
      ResolvedStatement resolved,
      Inputs inputs,
      Result<R> result,
      @Nullable OperationObservation observation) {
    Lookup<ConditionalPlan<R>> known = request.lookup;
    if (known != null) return cached(known.key(), observation, "merge", known.compile());
    return resolve(
        request,
        observation,
        "merge",
        () -> {
          ResolvedStatement returning = resolved.returning(result.columns());
          return conditionalLookup("merge", returning.statement(), returning, inputs, result);
        });
  }

  private <R> Lookup<ConditionalPlan<R>> conditionalLookup(
      String operation,
      StatementAst returning,
      @Nullable ResolvedStatement resolved,
      MutationQuerySupport.Inputs inputs,
      MutationQuerySupport.Result<R> result) {
    Optional<?> identity = result.cacheIdentity(registry);
    Key key =
        identity.isPresent() && inputs.cacheIdentity().isPresent()
            ? new Key(returning, identity.get(), inputs.cacheIdentity().orElseThrow())
            : null;
    List<JdbcTypeCodec<?>> codecs = inputs.codecs();
    int columnCount = result.columns().size();
    return new Lookup<>(
        key,
        () -> {
          RenderedSql sql = resolved == null ? render(returning) : render(resolved);
          CompiledMutationPlan<@Nullable Object[]> plan =
              new CompiledMutationPlan<>(
                  operation, dialect.id(), sql, new ValuesBinder(sql.parameters(), codecs));
          RowDecoder<R> decoder = result.decoder(registry);
          return new ConditionalPlan<>(plan, decoder, columnCount);
        });
  }

  <E, R, P> Plan<R, P> insert(
      Request<Plan<R, P>> request,
      StatementAst statement,
      EntityMutationPlanSet<E> entity,
      CompiledMutationPlan<P> base,
      MutationQuerySupport.Result<R> result,
      boolean readKey,
      @Nullable OperationObservation observation) {
    Lookup<Plan<R, P>> known = request.lookup;
    if (known != null) return cached(known.key(), observation, "insert", known.compile());
    return resolve(
        request,
        observation,
        "insert",
        () -> {
          ParameterBinder<P> binder = base.parameterBinder();
          List<ColumnExpression<?, ?>> columns = new ArrayList<>();
          for (var column : result.columns()) {
            columns.add(entity.returningColumn(column.property()));
          }
          int found = -1;
          if (readKey) {
            for (int index = 0; index < columns.size(); index++) {
              if (columns.get(index).property() == entity.idProperty()) {
                found = index;
                break;
              }
            }
            if (found < 0) {
              found = columns.size();
              columns.add(entity.returningColumn(entity.idProperty()));
            }
          }
          int keyIndex = found + 1;
          ReturningStatement returning = new ReturningStatement(statement, columns);
          Key key =
              result
                  .cacheIdentity(registry)
                  .map(identity -> new Key(returning, identity, List.of(readKey)))
                  .orElse(null);
          return new Lookup<>(
              key,
              () -> {
                RenderedSql sql = render(returning);
                if (!sql.parameters().equals(base.renderedSql().parameters())) {
                  throw MutationException.of(
                      SkisErrorCode.MUTATION_PARAMETER_SHAPE,
                      "RETURNING parameter layout cannot be consumed by the generated insert binder");
                }
                RowDecoder<R> decoder = result.decoder(registry);
                JdbcTypeCodec<?> keyCodec = entity.idCodec();
                RowDecoder<Returned<R>> rows =
                    (rs, context) -> {
                      R value =
                          Objects.requireNonNull(
                              decoder.decode(rs, context), "null returning value");
                      Object id = readKey ? keyCodec.read(rs, keyIndex, context) : null;
                      if (readKey && !entity.idProperty().javaType().isInstance(id)) {
                        throw MutationException.of(
                            SkisErrorCode.MUTATION_GENERATED_KEY_INVALID,
                            "RETURNING did not provide a valid generated primary key");
                      }
                      return new Returned<>(value, id);
                    };
                return new Plan<>(
                    new CompiledMutationPlan<>("insert", dialect.id(), sql, binder),
                    rows,
                    columns.size());
              });
        });
  }

  private RenderedSql render(StatementAst statement) {
    return rendered(SqlCompiler.compile(statement, dialect));
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

  /** Shares the existing bounded cache and only captures value-free compiler inputs. */
  <R> StructuredPlan<R> structured(
      Request<StructuredPlan<R>> request,
      StructuredWrites.Prepared prepared,
      MutationQuerySupport.@Nullable Result<R> result,
      boolean readKey,
      @Nullable OperationObservation observation) {
    String operation = prepared.merge() ? "merge" : "insert";
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
          List<ColumnExpression<?, ?>> columns = new ArrayList<>();
          if (result != null) columns.addAll(result.columns());
          int keyIndex = -1;
          if (readKey) {
            var id = Objects.requireNonNull(entity).idProperty();
            for (int i = 0; i < columns.size(); i++)
              if (columns.get(i).property() == id) keyIndex = i;
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
                RenderedSql sql = render(resolved);
                for (int i = 0; i < prefixCount; i++) {
                  if (i >= sql.parameters().size() || sql.parameters().get(i).ordinal() != i)
                    throw MutationException.of(
                        SkisErrorCode.MUTATION_PARAMETER_SHAPE,
                        "structured INSERT changed the generated binder prefix");
                }
                CompiledMutationPlan<StructuredWrites.Arguments> compiled =
                    new CompiledMutationPlan<>(
                        operation,
                        dialect.id(),
                        sql,
                        new StructuredBinder(
                            prefix,
                            prefixCount,
                            sql.parameters().subList(prefixCount, sql.parameters().size()),
                            codecs));
                if (outputCount == 0) return new StructuredPlan<>(compiled, null, 0);
                RowDecoder<R> values = result == null ? null : result.decoder(registry);
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
                      return new Returned<>(Objects.requireNonNull(value), id);
                    };
                return new StructuredPlan<>(compiled, decoder, outputCount);
              });
        });
  }

  record StructuredPlan<R>(
      CompiledMutationPlan<StructuredWrites.Arguments> statement,
      @Nullable RowDecoder<Returned<R>> decoder,
      int columnCount) {}

  private record StructuredBinder(
      @Nullable ParameterBinder<?> prefix,
      int prefixCount,
      List<ParameterSlot<?>> positions,
      List<JdbcTypeCodec<?>> codecs)
      implements ParameterBinder<StructuredWrites.Arguments> {

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
                  .bind(statement, firstIndex, Objects.requireNonNull(arguments.base()), context)
              != firstIndex + prefixCount)
        throw new SQLException("generated INSERT binder did not bind its declared prefix");
      for (int i = 0; i < positions.size(); i++) {
        int ordinal = positions.get(i).ordinal() - prefixCount;
        ValuesBinder.bindValue(
            codecs.get(ordinal),
            statement,
            firstIndex + prefixCount + i,
            arguments.values()[ordinal],
            context);
      }
      return firstIndex + prefixCount + positions.size();
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
                      firstIndex,
                      Objects.requireNonNull(arguments.base()),
                      context,
                      observer)
              != firstIndex + prefixCount)
        throw new SQLException("generated INSERT binder did not bind its declared prefix");
      boolean observing = observer.needsMore();
      for (int i = 0; i < positions.size(); i++) {
        ParameterSlot<?> slot = positions.get(i);
        int ordinal = slot.ordinal() - prefixCount, index = firstIndex + prefixCount + i;
        Object value = arguments.values()[ordinal];
        ValuesBinder.bindValue(codecs.get(ordinal), statement, index, value, context);
        if (observing) {
          observer.bound(index, slot.javaType(), value);
          observing = observer.needsMore();
        }
      }
      return firstIndex + prefixCount + positions.size();
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
          case Plan<?, ?> returning -> returning.statement();
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

  record Plan<R, P>(
      CompiledMutationPlan<P> statement, RowDecoder<Returned<R>> decoder, int columnCount) {}

  record ConditionalPlan<R>(
      CompiledMutationPlan<@Nullable Object[]> statement, RowDecoder<R> decoder, int columnCount) {}

  record Returned<R>(R value, @Nullable Object key) {
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
