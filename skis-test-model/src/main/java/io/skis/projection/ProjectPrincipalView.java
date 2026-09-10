package io.skis.projection;

import io.skis.annotations.SkisProjection;
import org.jspecify.annotations.Nullable;

/** Project row with a principal that may be absent on the LEFT JOIN side. */
@SkisProjection
public record ProjectPrincipalView(
    long projectId,
    String projectName,
    @Nullable Long principalId,
    @Nullable String principalName) {}
