package io.skis.projection;

import io.skis.annotations.ReusableProjection;

/** Employee row enriched with its department identity and name. */
@ReusableProjection
public record EmployeeDepartmentView(
    long employeeId,
    String employeeCode,
    String employeeName,
    long departmentId,
    String departmentName) {}
