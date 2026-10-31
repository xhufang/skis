package io.skis.sql.ast;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Source joins resolve before the writable target, so they cannot null-extend that target. */
final class MutationScope {
  private MutationScope() {}

  static FromClause of(TableExpression<?> target, @Nullable FromClause sources) {
    if (sources == null) return FromClause.of(target);
    List<JoinClause> joins = new ArrayList<>(sources.joins().size() + 1);
    joins.addAll(sources.joins());
    joins.add(new JoinClause(JoinType.CROSS, target, null));
    return new FromClause(sources.root(), joins);
  }
}
