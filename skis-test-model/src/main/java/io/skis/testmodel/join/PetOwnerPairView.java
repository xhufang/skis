package io.skis.testmodel.join;

import io.skis.annotations.SkisProjection;
import org.jspecify.annotations.Nullable;

/** Projection that proves two aliases of one physical entity remain distinct occurrences. */
@SkisProjection
public record PetOwnerPairView(
    long petId, @Nullable String ownerName, @Nullable String reviewerName) {}
