package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.dialect.spi.Occurrence;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.InSubqueryPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SqlExpression;
import java.util.IdentityHashMap;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Compile-local mapping checks over the complete original tree, independently of result readers.
 */
final class QueryInputMappings {

  private final EntityRuntimeRegistry registry;
  private final @Nullable ResolvedStatement resolved;

  private QueryInputMappings(EntityRuntimeRegistry registry, @Nullable ResolvedStatement resolved) {
    this.registry = registry;
    this.resolved = resolved;
  }

  static void validate(ResolvedStatement resolved, EntityRuntimeRegistry registry) {
    if (!(resolved.statement() instanceof SelectStatement statement)) {
      throw new IllegalArgumentException("input mapping validation requires a SELECT statement");
    }
    QueryInputMappings mappings = new QueryInputMappings(registry, resolved);
    mappings.new Block(null, statement.fromClause()).scan(statement);
  }

  static void validateValue(SqlExpression<?> expression, EntityRuntimeRegistry registry) {
    if (expression instanceof LiteralExpression<?>
        || expression instanceof ParameterSlot<?>
        || expression instanceof BooleanPredicate
        || (expression instanceof AggregateExpression<?> aggregate
            && aggregate.kind() == AggregateExpression.Kind.COUNT
            && !aggregate.distinct())) {
      ResolvedValueMapping.requireBuiltinCodec(expression.javaType(), expression.sqlType());
      return;
    }
    new QueryInputMappings(registry, null).new Block(null, null).requireBuiltin(expression);
  }

  private <E, V> JdbcTypeCodec<V> propertyCodec(ColumnExpression<E, V> column) {
    return registry.require(column.table().entity()).property(column.property()).codec();
  }

  private static QueryValidationException mismatch(SqlExpression<?> expression) {
    String input =
        switch (expression) {
          case ColumnExpression<?, ?> column ->
              "property '"
                  + column.table().alias().map(alias -> alias.value())
                      .orElse(column.table().entity().table().name())
                  + '.'
                  + column.property().name()
                  + "' (#"
                  + column.property().ordinal()
                  + ')';
          case DerivedColumnExpression<?> column ->
              "derived output '"
                  + column.relation().alias().value()
                  + '.'
                  + column.output().name().value()
                  + "' (#"
                  + column.outputOrdinal()
                  + ')';
          default -> expression.getClass().getSimpleName();
        };
    return QueryValidationException.of(
        SkisErrorCode.QUERY_TYPE_MISMATCH,
        "standard expression or aggregate input requires the exact built-in Codec for " + input);
  }

  /** Adds a location only on failure; successful mapping checks allocate no diagnostic objects. */
  static QueryValidationException failureAt(
      QueryValidationException failure, String blockPath, String clause, int itemOrdinal) {
    if (failure.code() != SkisErrorCode.QUERY_TYPE_MISMATCH) {
      return failure;
    }
    SkisDiagnostics original = failure.diagnostics();
    SkisDiagnostics.QueryLocation location = original.query();
    if (location != null && location.blockPath() != null) {
      return failure;
    }
    SkisDiagnostics.Facts facts = Objects.requireNonNull(original.facts());
    SkisDiagnostics.Builder diagnostics =
        SkisDiagnostics.builder(original.stage())
            .queryBlock(blockPath)
            .clause(clause)
            .expressionPath(clause + '[' + itemOrdinal + ']')
            .types(facts.expectedType(), facts.actualType())
            .detail(Objects.requireNonNull(facts.detail()));
    if (facts.observedCount() != null) {
      diagnostics.observedCount(facts.observedCount());
    }
    if (location != null) {
      if (location.sourceIndex() != null) {
        diagnostics.sourceIndex(location.sourceIndex());
      }
      if (location.sourceAlias() != null) {
        diagnostics.sourceAlias(location.sourceAlias());
      }
    }
    return QueryValidationException.of(failure.code(), diagnostics.build(), failure.getCause());
  }

  private final class Block {

    private final @Nullable Block parent;
    private @Nullable IdentityHashMap<DerivedRelationReference, RelationSource> sources;
    private @Nullable IdentityHashMap<SelectStatement, Block> children;
    private final IdentityHashMap<SqlExpression<?>, Boolean> scanned = new IdentityHashMap<>();
    private final IdentityHashMap<SqlExpression<?>, Boolean> builtin = new IdentityHashMap<>();
    private boolean statementScanned;

    private Block(@Nullable Block parent, @Nullable FromClause from) {
      this.parent = parent;
      if (from != null) {
        register(from.root());
        for (var join : from.joins()) {
          register(join.right());
        }
      }
    }

    private void register(RelationSource source) {
      DerivedRelationReference reference = source.derivedReference().orElse(null);
      if (reference != null) {
        if (sources == null) {
          sources = new IdentityHashMap<>();
        }
        sources.put(reference, source);
      }
    }

    private Block child(SelectStatement statement) {
      if (children == null) {
        children = new IdentityHashMap<>();
      }
      return children.computeIfAbsent(statement, item -> new Block(this, item.fromClause()));
    }

    private void scan(SelectStatement statement) {
      if (statementScanned) {
        return;
      }
      statementScanned = true;
      scanSource(statement.fromClause().root());
      for (var join : statement.joins()) {
        scanSource(join.right());
        join.on().ifPresent(this::scanRoot);
      }
      statement.selections().forEach(this::scanRoot);
      statement.hiddenSelections().forEach(item -> scanRoot(item.expression()));
      statement.where().ifPresent(this::scanRoot);
      statement.groupBy().forEach(this::scanRoot);
      statement.having().ifPresent(this::scanRoot);
      statement.orderBy().forEach(item -> scanRoot(item.expression()));
      statement
          .pagination()
          .ifPresent(
              item -> {
                if (item instanceof KeysetSeek keyset) {
                  scanRoot(keyset.predicate());
                }
              });
    }

    private void scanRoot(SqlExpression<?> expression) {
      try {
        scan(expression);
      } catch (QueryValidationException failure) {
        if (resolved != null && failure.code() == SkisErrorCode.QUERY_TYPE_MISMATCH) {
          for (Occurrence occurrence : resolved.occurrences()) {
            if (occurrence.expression() == expression) {
              throw failureAt(
                  failure,
                  occurrence.blockPath().toString(),
                  occurrence.clause().displayName(),
                  occurrence.itemOrdinal());
            }
          }
        }
        throw failure;
      }
    }

    private void scanSource(RelationSource source) {
      if (source instanceof DerivedRelationSource derived) {
        child(derived.statement()).scan(derived.statement());
      }
    }

    private void scan(SqlExpression<?> expression) {
      if (scanned.put(expression, Boolean.TRUE) != null) {
        return;
      }
      switch (expression) {
        case AggregateExpression<?> aggregate ->
            aggregate
                .operand()
                .ifPresent(
                    input -> {
                      if (aggregate.kind() != AggregateExpression.Kind.COUNT
                          || aggregate.distinct()) {
                        requireBuiltin(input);
                      }
                      scan(input);
                    });

        case ArithmeticExpression<?> arithmetic -> {
          scan(arithmetic.left());
          scan(arithmetic.right());
        }
        case CaseExpression<?> conditional -> {
          for (var branch : conditional.branches()) {
            scan(branch.condition());
            scan(branch.result());
          }
          conditional.otherwise().ifPresent(this::scan);
        }
        case CoalesceExpression<?> coalesce -> coalesce.operands().forEach(this::scan);
        case ConcatExpression concat -> concat.operands().forEach(this::scan);
        case CastExpression<?> cast -> scan(cast.operand());
        case IncrementExpression<?> increment -> scan(increment.operand());
        case ComparisonPredicate<?> comparison -> {
          scan(comparison.left());
          scan(comparison.right());
        }
        case BetweenPredicate<?> between -> {
          scan(between.value());
          scan(between.lower());
          scan(between.upper());
        }
        case LogicalPredicate logical -> logical.operands().forEach(this::scan);
        case NotPredicate not -> scan(not.operand());
        case NullPredicate check -> scan(check.operand());
        case LikePredicate like -> {
          scan(like.value());
          scan(like.pattern());
        }
        case InPredicate<?> membership -> {
          scan(membership.value());
          membership.candidates().forEach(this::scan);
        }
        case InSubqueryPredicate<?> membership -> {
          scan(membership.value());
          child(membership.subquery()).scan(membership.subquery());
        }
        case ScalarSubqueryExpression<?> scalar -> child(scalar.subquery()).scan(scalar.subquery());
        case ExistsPredicate exists -> child(exists.subquery()).scan(exists.subquery());
        case ColumnExpression<?, ?> ignored -> {}
        case DerivedColumnExpression<?> ignored -> {}
        case LiteralExpression<?> ignored -> {}
        case ParameterSlot<?> ignored -> {}
        case BooleanPredicate ignored -> {}
        case SelectedOutputReference<?> ignored -> {}
        default -> throw mismatch(expression);
      }
    }

    /**
     * Follows value provenance only; CASE conditions and COUNT inputs do not supply result values.
     */
    private void requireBuiltin(SqlExpression<?> expression) {
      if (builtin.containsKey(expression)) {
        return;
      }
      JdbcTypeCodec<?> expected =
          ResolvedValueMapping.requireBuiltinCodec(expression.javaType(), expression.sqlType());
      switch (expression) {
        case ColumnExpression<?, ?> column -> {
          if (propertyCodec(column) != expected) {
            throw mismatch(column);
          }
        }
        case DerivedColumnExpression<?> column -> requireDerived(column);
        case ScalarSubqueryExpression<?> scalar ->
            child(scalar.subquery()).requireBuiltin(scalar.subquery().selections().getFirst());
        case AggregateExpression<?> aggregate -> {
          if (aggregate.kind() != AggregateExpression.Kind.COUNT || aggregate.distinct()) {
            aggregate.operand().ifPresent(this::requireBuiltin);
          }
        }
        case ArithmeticExpression<?> arithmetic -> {
          requireBuiltin(arithmetic.left());
          requireBuiltin(arithmetic.right());
        }
        case CaseExpression<?> conditional -> {
          conditional.branches().forEach(branch -> requireBuiltin(branch.result()));
          conditional.otherwise().ifPresent(this::requireBuiltin);
        }
        case CoalesceExpression<?> coalesce -> coalesce.operands().forEach(this::requireBuiltin);
        case ConcatExpression concat -> concat.operands().forEach(this::requireBuiltin);
        case CastExpression<?> cast -> requireBuiltin(cast.operand());
        case IncrementExpression<?> increment -> requireBuiltin(increment.operand());
        case LiteralExpression<?> ignored -> {}
        case ParameterSlot<?> ignored -> {}
        case BooleanPredicate ignored -> {}
        default -> throw mismatch(expression);
      }
      builtin.put(expression, Boolean.TRUE);
    }

    private void requireDerived(DerivedColumnExpression<?> column) {
      RelationSource source = sources == null ? null : sources.get(column.relation());
      switch (source) {
        case null -> {
          if (parent == null) {
            throw mismatch(column);
          }
          parent.requireDerived(column);
        }
        case DerivedRelationSource derived ->
            child(derived.statement())
                .requireBuiltin(derived.statement().selections().get(column.outputOrdinal()));
        case ParameterRelationSource parameters ->
            requireBuiltin(parameters.parameters().get(column.outputOrdinal()));
        default -> throw mismatch(column);
      }
    }
  }
}
