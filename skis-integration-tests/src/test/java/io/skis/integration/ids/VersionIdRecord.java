package io.skis.integration.ids;

import io.skis.annotations.*;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "versionidrecord")
public record VersionIdRecord(
    @Id(strategy = IdStrategy.UUID) @Nullable UUID id,
    @Version @Nullable Long version, @Column(nullable = false) String name) {}

