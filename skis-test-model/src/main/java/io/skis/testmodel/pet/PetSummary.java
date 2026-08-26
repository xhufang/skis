package io.skis.testmodel.pet;

import io.skis.annotations.SkisProjection;
import java.math.BigDecimal;

/** User-owned projection type used by integration tests. */
@SkisProjection(entity = Pet.class)
public record PetSummary(Long id, String name, BigDecimal weight) {}
