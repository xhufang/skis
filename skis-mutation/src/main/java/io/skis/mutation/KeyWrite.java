package io.skis.mutation;

import java.util.function.Consumer;

/** An explicit KEY awaiting its non-empty row of column/value bindings. */
public interface KeyWrite<E> {
  SingleWrite<E> values(Consumer<ValueRow> values);
}
