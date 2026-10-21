package io.skis.testmodel.join;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import org.jspecify.annotations.Nullable;

/** Pet-side test entity for explicit Join contracts. */
@SkisEntity
@Table(schema = "shelter", name = "skis_join_pet")
public record JoinPet(
    @Id long id,
    @Column(name = "owner_id") @Nullable Long ownerId,
    @Column(name = "pet_name", nullable = false, length = 200) String name) {}
