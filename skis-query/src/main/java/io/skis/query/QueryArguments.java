package io.skis.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Immutable ordered values supplied to one compiled query plan. */
record QueryArguments(List<@Nullable Object> values) {

  QueryArguments {
    values = Collections.unmodifiableList(new ArrayList<>(values));
  }
}
