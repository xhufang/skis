package io.skis.service;

import io.skis.core.id.IdWorker;
import io.skis.entity.Department;
import io.skis.entity.Employee;
import io.skis.entity.skis.DepartmentTable;
import io.skis.entity.skis.EmployeeTable;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.runtime.SkisExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Department CRUD and employee lookup examples. */
public class DepartmentServiceImpl implements DepartmentService {

  private static final DepartmentTable DEPARTMENT = DepartmentTable.departmentTable;
  private static final EmployeeTable EMPLOYEE = EmployeeTable.employeeTable;

  private final SkisExecutor skisExecutor;

  public DepartmentServiceImpl(SkisExecutor skisExecutor) {
    this.skisExecutor = Objects.requireNonNull(skisExecutor, "skisExecutor");
  }

  @Override
  public Department createDepartment(Department department) {
    Objects.requireNonNull(department, "department");
    Instant now = Instant.now();
    department.setId(IdWorker.getInstance().nextId());
    department.setCreateStamp(now);
    department.setModifyStamp(now);
    department.setVersion(0L);
    skisExecutor.insert(DEPARTMENT, department);
    return department;
  }

  @Override
  public int updateDepartment(Department department) {
    Objects.requireNonNull(department, "department");
    department.setModifyStamp(Instant.now());
    return skisExecutor.updateById(DEPARTMENT, department);
  }

  @Override
  public int deleteDepartment(long id) {
    return skisExecutor.deleteById(DEPARTMENT, id);
  }

  @Override
  public Optional<Department> findById(long id) {
    return skisExecutor.findById(DEPARTMENT, id);
  }

  @Override
  public List<Department> findAll() {
    return skisExecutor.selectFrom(DEPARTMENT).orderBy(DEPARTMENT.id().asc()).fetchList();
  }

  @Override
  public List<Department> findByNamePattern(String pattern) {
    return skisExecutor
        .selectFrom(DEPARTMENT)
        .where(DEPARTMENT.name().like(pattern))
        .orderBy(DEPARTMENT.id().asc())
        .fetchList();
  }

  @Override
  public List<Employee> findEmployees(long departmentId) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .where(EMPLOYEE.departmentId().eq(departmentId))
        .orderBy(EMPLOYEE.id().asc())
        .fetchList();
  }

  @Override
  public Page<Employee> findEmployees(long departmentId, int pageIndex, int pageSize) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .where(EMPLOYEE.departmentId().eq(departmentId))
        .orderBy(EMPLOYEE.id().asc())
        .fetchPage(PageRequest.page(pageIndex, pageSize));
  }
}
