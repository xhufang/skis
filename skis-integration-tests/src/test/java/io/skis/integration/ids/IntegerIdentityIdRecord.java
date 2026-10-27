package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "integeridentityidrecord")
public record IntegerIdentityIdRecord(
    @Id(strategy = IdStrategy.IDENTITY) @Nullable Integer id, @Column(nullable = false) String name) {}

