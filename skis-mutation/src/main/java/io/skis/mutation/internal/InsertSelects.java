package io.skis.mutation.internal;

import io.skis.core.spi.ExecutionOperationKind;
import io.skis.metadata.IdGeneration;
import io.skis.mutation.InsertInto;
import io.skis.mutation.InsertSelect;
import io.skis.mutation.InsertSelectWrite;
import io.skis.query.QueryTable;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.WriteInputCompiler;
import io.skis.sql.ast.InsertStatement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One native INSERT SELECT; never materializes source rows or invokes per-row ID generators. */
final class InsertSelects {
  private InsertSelects() {}

  static <E> InsertSelect<E> columns(
      InsertInto<E> stage, Selectable<?> first, Selectable<?>[] remaining) {
    var owner = MutationRequests.insertOwner(stage);
    List<Selectable<?>> columns = new ArrayList<>(remaining.length + 1);
    columns.add(Objects.requireNonNull(first, "first"));
    for (Selectable<?> column : remaining) columns.add(Objects.requireNonNull(column, "column"));
    var key = owner.table().entity().primaryKey().orElseThrow();
    var keyProperty = key.properties().getFirst();
    BitSet seen = new BitSet(owner.table().entity().properties().size());
    boolean supplied = false;
    for (Selectable<?> column : columns) {
      var property = MutationQuerySupport.column(owner.table(), column).property();
      if (!property.column().insertable() || seen.get(property.ordinal()))
        throw StructuredWrites.invalid("duplicate or non-insertable INSERT SELECT target");
      seen.set(property.ordinal());
      supplied |= property == keyProperty;
    }
    if ((key.generation() == IdGeneration.IDENTITY) == supplied)
      throw StructuredWrites.invalid(
          "INSERT SELECT must obey its explicit primary-key source contract");
    List<Selectable<?>> snapshot = List.copyOf(columns);
    return query ->
        new Request<>(
            owner.operations(), owner.table(), snapshot, Objects.requireNonNull(query, "query"));
  }

  private static final class Request<E> extends MutationRequests.WriteRequest<E>
      implements InsertSelectWrite<E> {
    private final List<Selectable<?>> columns;
    private final SelectSource<?> query;

    Request(
        DefaultMutationOperations operations,
        QueryTable<E> table,
        List<Selectable<?>> columns,
        SelectSource<?> query) {
      super(operations, table);
      this.columns = columns;
      this.query = query;
    }

    @Override
    ExecutionOperationKind operationKind() {
      return ExecutionOperationKind.INSERT;
    }

    @Override
    MutationRequests.Prepared compile(MutationQuerySupport.@Nullable Result<?> returning) {
      operations.requireWritable(table);
      WriteInputCompiler compiler =
          new WriteInputCompiler(operations.dynamicPlans().registry(), table, 0);
      var source = compiler.query(query);
      var targets = compiler.columns(columns);
      var statement = InsertStatement.select(MutationQuerySupport.table(table), targets, source);
      compiler.returning(returning);
      var inputs = compiler.finish();
      WriteCodecs.requireMatching(
          operations.dynamicPlans().registry(), targets, source, inputs.codecs(), "INSERT SELECT");
      return new MutationRequests.Prepared(statement, inputs);
    }
  }
}
