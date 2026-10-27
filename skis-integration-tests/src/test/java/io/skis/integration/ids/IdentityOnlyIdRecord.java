package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "identityonlyidrecord")
public record IdentityOnlyIdRecord(
    @Id(strategy = IdStrategy.IDENTITY) @Nullable Long id) {}

