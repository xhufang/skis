package example;

import example.skis.PetTable;
import io.skis.annotations.PropertyFrom;
import io.skis.annotations.SpecialProjection;

@SpecialProjection
public record PetView(@PropertyFrom(PetTable.NAME) String name) {}
