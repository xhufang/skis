package io.skis.mutation;

import java.util.Collection;

/** Entity inputs preserve generated primary-key and optimistic-version behavior. */
public interface EntityById<E> {

  SingleWrite<E> value(E entity);

  BatchWrite<E> values(Collection<? extends E> entities);
}
