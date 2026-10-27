package io.skis.integration.ids;

import io.skis.annotations.*;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "uuididrecord")
public record UuidIdRecord(
    @Id(strategy = IdStrategy.UUID) @Nullable UUID id, @Column(nullable = false) String name) {}

