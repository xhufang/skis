package io.skis.integration.write;

import io.skis.annotations.ReusableProjection;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@ReusableProjection
public record WriteView(String name, @Nullable String note) {}
