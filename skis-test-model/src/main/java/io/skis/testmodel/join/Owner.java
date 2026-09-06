package io.skis.testmodel.join;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;

/** Owner-side test entity for explicit Join contracts. */
@SkisEntity
@Table(schema = "shelter", name = "skis_join_owner")
public record Owner(
    @Id long id,
    @Column(name = "owner_name", nullable = false, length = 200) String name) {}
