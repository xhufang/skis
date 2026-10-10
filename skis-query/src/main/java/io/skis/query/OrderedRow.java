package io.skis.query;

import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** User row plus the internal ordering values needed to create a continuation. */
record OrderedRow<R>(@Nullable R value, List<@Nullable Object> orderValues) {

  OrderedRow {
    // The decoder transfers its fresh row-local list here; the wrapper prevents later mutation
    // without copying every keyset anchor row a second time.
    orderValues = Collections.unmodifiableList(orderValues);
  }
}
