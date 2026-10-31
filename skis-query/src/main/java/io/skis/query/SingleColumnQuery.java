package io.skis.query;

import org.jspecify.annotations.Nullable;

/** A framework-owned SELECT with exactly one visible SQL value, independent of its executor. */
public interface SingleColumnQuery<R extends @Nullable Object> extends SelectSource<R> {}
