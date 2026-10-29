package io.skis.mutation;

import io.skis.query.Selectable;
import io.skis.query.ValuesSource;

/** Chooses the independent USING or KEY grammar of a MERGE. */
public interface MergeInto<E> {

  MergeOn<E> using(ValuesSource source);

  KeyWrite<E> key(Selectable<?> first, Selectable<?>... remaining);
}
