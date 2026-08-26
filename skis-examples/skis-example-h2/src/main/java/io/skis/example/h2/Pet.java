package io.skis.example.h2;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;

@SkisEntity
@Table(name = "pet")
public record Pet(
    @Id long id,
    @Column(name = "pet_name", nullable = false, length = 200) String name,
    @Version Long version) {}
