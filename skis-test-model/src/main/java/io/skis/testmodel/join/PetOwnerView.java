package io.skis.testmodel.join;

import io.skis.annotations.SkisProjection;
import org.jspecify.annotations.Nullable;

/** Generated result-row contract used by single-table and cross-table projection tests. */
@SkisProjection
public record PetOwnerView(long petId, String petName, @Nullable String ownerName) {}
