package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "assignedidrecord")
public record AssignedIdRecord(
    @Id(strategy = IdStrategy.ASSIGNED) @Nullable Long id, @Column(nullable = false) String name) {}

