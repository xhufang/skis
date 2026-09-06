package io.skis.testmodel.pet;

import io.skis.annotations.SkisProjection;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** User-owned projection type used by integration tests. */
@SkisProjection
public record PetSummary(Long id, String name, @Nullable BigDecimal weight) {}
