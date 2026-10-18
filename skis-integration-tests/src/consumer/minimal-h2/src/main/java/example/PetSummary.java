package example;

import io.skis.annotations.ReusableProjection;

@ReusableProjection
public record PetSummary(Long id, String name) {}
