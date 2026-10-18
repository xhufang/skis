package io.skis.testmodel.join;

import io.skis.annotations.ReusableProjection;
import org.jspecify.annotations.Nullable;

/** Generated result-row contract used by single-table and cross-table projection tests. */
@ReusableProjection
public record PetOwnerView(long petId, String petName, @Nullable String ownerName) {}
