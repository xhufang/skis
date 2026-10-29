package io.skis.mutation;

public interface MergeActions<E> {

  MergeMatched<E> whenMatched();

  MergeNotMatched<E> whenNotMatched();
}
