package io.skis.mutation;

import io.skis.query.DerivedRelation;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.ValuesSource;

/** Chooses the independent USING or KEY grammar of a MERGE. */
public interface MergeInto<E> {

  MergeOn<E> using(ValuesSource source);

  MergeOn<E> using(QueryTable<?> source);

  MergeOn<E> using(DerivedRelation source);

  KeyWrite<E> key(Selectable<?> first, Selectable<?>... remaining);
}
