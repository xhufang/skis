package io.skis.query;

import org.jspecify.annotations.Nullable;

/** A fixed SQL result row; component nullability follows the selected expressions. */
public record Row3<
    A extends @Nullable Object, B extends @Nullable Object, C extends @Nullable Object>(
    A first, B second, C third) {}
