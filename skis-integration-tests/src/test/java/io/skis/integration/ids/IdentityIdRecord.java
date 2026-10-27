package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "identityidrecord")
public record IdentityIdRecord(
    @Id(strategy = IdStrategy.IDENTITY) @Nullable Long id, @Column(nullable = false) String name, @Transient @Nullable String note) {}

