package io.skis.projection;

import io.skis.annotations.ReusableProjection;

/** Employee and project data selected through the link table. */
@ReusableProjection
public record EmployeeProjectView(
    long employeeId,
    String employeeName,
    long projectId,
    String projectName,
    String projectStatus) {}
