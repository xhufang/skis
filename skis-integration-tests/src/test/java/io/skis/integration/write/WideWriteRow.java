package io.skis.integration.write;

import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "wide_write_row")
public record WideWriteRow(
    @Id Long id, @Nullable Long v1, @Nullable Long v2, @Nullable Long v3,
    @Nullable Long v4, @Nullable Long v5, @Nullable Long v6,
    @Nullable Long v7, @Nullable Long v8) {}
