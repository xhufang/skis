package io.skis.integration.write;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "write_row")
public record WriteRow(@Id Long id, @Column(nullable = false) String name,
    @Nullable String note, @Version @Nullable Long version) {}
