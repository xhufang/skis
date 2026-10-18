package io.skis.testmodel.pet;

import io.skis.annotations.ReusableProjection;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** User-owned projection type used by integration tests. */
@ReusableProjection
public record PetSummary(Long id, String name, @Nullable BigDecimal weight) {}
