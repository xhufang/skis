package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "snowflakeidrecord")
public record SnowflakeIdRecord(
    @Id(strategy = IdStrategy.SNOWFLAKE) @Nullable Long id, @Column(nullable = false) String name) {}

