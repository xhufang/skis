package io.skis.projection;

import io.skis.annotations.SkisProjection;

/** Employee row enriched with its department identity and name. */
@SkisProjection
public record EmployeeDepartmentView(
    long employeeId,
    String employeeCode,
    String employeeName,
    long departmentId,
    String departmentName) {}
