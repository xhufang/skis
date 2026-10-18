package io.skis.projection;

import io.skis.annotations.PropertyFrom;
import io.skis.annotations.SpecialProjection;
import io.skis.entity.skis.EmployeeTable;
import io.skis.entity.skis.ProjectTable;
import org.jspecify.annotations.Nullable;

/** Project row with a principal that may be absent on the LEFT JOIN side. */
@SpecialProjection
public record ProjectPrincipalView(
    @PropertyFrom(ProjectTable.ID) long projectId,
    @PropertyFrom(ProjectTable.NAME) String projectName,
    @PropertyFrom(EmployeeTable.ID) @Nullable Long principalId,
    @PropertyFrom(EmployeeTable.NAME) @Nullable String principalName) {}
