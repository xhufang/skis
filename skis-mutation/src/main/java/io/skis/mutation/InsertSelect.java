package io.skis.mutation;

import io.skis.query.SelectSource;

/** An explicit INSERT target list awaiting one SELECT source. */
public interface InsertSelect<E> {

  InsertSelectWrite<E> select(SelectSource<?> query);
}
