package io.skis.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** User row plus the internal ordering values needed to create a continuation. */
record OrderedRow<R>(@Nullable R value, List<@Nullable Object> orderValues) {

  OrderedRow {
    orderValues = Collections.unmodifiableList(new ArrayList<>(orderValues));
  }
}
