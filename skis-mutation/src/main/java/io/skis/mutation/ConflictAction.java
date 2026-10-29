package io.skis.mutation;

public interface ConflictAction<E> {

  SingleWrite<E> doNothing();

  ConflictSet<E> doUpdate();
}
