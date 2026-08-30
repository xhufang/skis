package example;

import io.skis.annotations.SkisProjection;

@SkisProjection(entity = Pet.class)
public record PetSummary(Long id, String name) {}
