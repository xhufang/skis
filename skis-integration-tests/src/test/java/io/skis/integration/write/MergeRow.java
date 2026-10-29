package io.skis.integration.write;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "merge_row")
public record MergeRow(@Id Long id, @Column(nullable = false) String name, @Nullable String note) {}
