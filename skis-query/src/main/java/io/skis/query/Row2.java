package io.skis.query;

import org.jspecify.annotations.Nullable;

/** A fixed SQL result row; component nullability follows the selected expressions. */
public record Row2<A extends @Nullable Object, B extends @Nullable Object>(A first, B second) {}
