package example;

import io.skis.annotations.SkisProjection;

@SkisProjection
public record PetSummary(Long id, String name) {}
