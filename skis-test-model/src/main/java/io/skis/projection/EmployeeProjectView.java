package io.skis.projection;

import io.skis.annotations.SkisProjection;

/** Employee and project data selected through the link table. */
@SkisProjection
public record EmployeeProjectView(
    long employeeId,
    String employeeName,
    long projectId,
    String projectName,
    String projectStatus) {}
