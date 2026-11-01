package io.skis.mutation.internal;

import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.RowAssignment;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SetAssignment;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Positional Codec checks for column groups fed by one SELECT; runs once per prepared shape. */
final class WriteCodecs {
  private WriteCodecs() {}

  static void requireRowAssignments(
      EntityRuntimeRegistry registry,
      List<? extends SetAssignment> assignments,
      @Nullable FromClause outerSources,
      List<JdbcTypeCodec<?>> parameterCodecs) {
    for (SetAssignment assignment : assignments) {
      if (assignment
          instanceof RowAssignment(List<ColumnExpression<?, ?>> columns, SelectStatement query))
        requireMatching(registry, columns, query, outerSources, parameterCodecs, "row assignment");
    }
  }

  /** Every query output, including parameters and computations, must use the target Codec. */
  static void requireMatching(
      EntityRuntimeRegistry registry,
      List<? extends ColumnExpression<?, ?>> targets,
      SelectStatement query,
      List<JdbcTypeCodec<?>> parameterCodecs,
      String shape) {
    requireMatching(registry, targets, query, null, parameterCodecs, shape);
  }

  private static void requireMatching(
      EntityRuntimeRegistry registry,
      List<? extends ColumnExpression<?, ?>> targets,
      SelectStatement query,
      @Nullable FromClause outerSources,
      List<JdbcTypeCodec<?>> parameterCodecs,
      String shape) {
    List<JdbcTypeCodec<?>> sources =
        MutationQuerySupport.queryOutputCodecs(registry, query, outerSources, parameterCodecs);
    int count = Math.min(targets.size(), sources.size());
    for (int index = 0; index < count; index++) {
      JdbcTypeCodec<?> expected = codec(registry, targets.get(index));
      if (!expected.equals(sources.get(index)))
        throw StructuredWrites.invalid(
            shape + " target and source use different JDBC codecs at position " + (index + 1));
    }
  }

  private static <E, V> JdbcTypeCodec<V> codec(
      EntityRuntimeRegistry registry, ColumnExpression<E, V> column) {
    return registry.require(column.table().entity()).property(column.property()).codec();
  }
}
