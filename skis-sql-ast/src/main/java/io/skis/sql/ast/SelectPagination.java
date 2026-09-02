package io.skis.sql.ast;

/** Marker for immutable, parameterized SELECT pagination nodes. */
public sealed interface SelectPagination permits Limit, OffsetLimit, KeysetSeek {

  /** Parameter slot that supplies the SQL row limit. */
  ParameterSlot<Integer> limit();
}
