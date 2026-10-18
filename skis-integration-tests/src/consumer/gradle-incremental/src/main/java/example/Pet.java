package example;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;

@SkisEntity(readOnly = true)
public record Pet(@Id long id, @Column(nullable = false) String name) {}
