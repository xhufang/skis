package io.skis.testmodel.pet;

import io.skis.annotations.ReusableProjection;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.jspecify.annotations.Nullable;

/** APT fixture mixing grouping, widening, exact decimal and scalar-subquery results. */
@ReusableProjection
public record AggregateSummary(
    String category,
    long count,
    @Nullable BigInteger idSum,
    @Nullable BigDecimal average,
    @Nullable Long scalarCount) {}
