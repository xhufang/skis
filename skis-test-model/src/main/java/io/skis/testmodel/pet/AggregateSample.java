package io.skis.testmodel.pet;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** Generated entity fixture for aggregate and standard-expression driver contracts. */
@SkisEntity
@Table(name = "aggregate_samples")
public record AggregateSample(
    @Id long id,
    @Column(nullable = false) String category,
    @Nullable Integer number,
    @Nullable BigDecimal amount) {}
