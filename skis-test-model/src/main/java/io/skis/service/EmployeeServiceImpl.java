package io.skis.service;

import io.skis.entity.Employee;
import io.skis.entity.EmployeeProjectLink;
import io.skis.entity.skis.DepartmentTable;
import io.skis.entity.skis.EmployeeProjectLinkTable;
import io.skis.entity.skis.EmployeeTable;
import io.skis.entity.skis.ProjectTable;
import io.skis.projection.EmployeeDepartmentView;
import io.skis.projection.EmployeeProjectView;
import io.skis.projection.skis.EmployeeDepartmentViewPro;
import io.skis.projection.skis.EmployeeProjectViewPro;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.runtime.SkisExecutor;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Employee use cases demonstrating Fast Path CRUD, predicates, pagination, joins, projections, and
 * transactions.
 *
 * @author Hu Xin
 */
public class EmployeeServiceImpl implements EmployeeService {

  private static final DepartmentTable DEPARTMENT = DepartmentTable.departmentTable;
  private static final EmployeeTable EMPLOYEE = EmployeeTable.employeeTable;
  private static final EmployeeProjectLinkTable LINK =
      EmployeeProjectLinkTable.employeeProjectLinkTable;
  private static final ProjectTable PROJECT = ProjectTable.projectTable;

  private final SkisExecutor skisExecutor;

  public EmployeeServiceImpl(SkisExecutor skisExecutor) {
    this.skisExecutor = Objects.requireNonNull(skisExecutor, "skisExecutor");
  }

  @Override
  public Employee createEmployee(Employee employee) {
    prepareForInsert(employee);
    skisExecutor.insertInto(EMPLOYEE).value(employee).write();
    return employee;
  }

  @Override
  public Employee createEmployeeWithProjects(Employee employee, List<Long> projectIds) {
    Objects.requireNonNull(projectIds, "projectIds");
    List<Long> distinctProjectIds = List.copyOf(new LinkedHashSet<>(projectIds));
    prepareForInsert(employee);

    return skisExecutor.inTransaction(
        session -> {
          session.insertInto(EMPLOYEE).value(employee).write();
          for (Long projectId : distinctProjectIds) {
            session.insertInto(LINK)
                .value(newLink(Objects.requireNonNull(employee.getId(), "employee id"), projectId))
                .write();
          }
          return employee;
        });
  }

  @Override
  public int updateEmployee(Employee employee) {
    Objects.requireNonNull(employee, "employee");
    employee.setModifyStamp(Instant.now());
    return skisExecutor.updateById(EMPLOYEE, employee);
  }

  @Override
  public int deleteEmployee(long id) {
    return skisExecutor.deleteById(EMPLOYEE, id);
  }

  @Override
  public Optional<Employee> findById(long id) {
    return skisExecutor.findById(EMPLOYEE, id);
  }

  @Override
  public Optional<Employee> findOneByCode(String code) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .where(EMPLOYEE.code().eq(code))
        .fetchOne()
        .toOptional();
  }

  @Override
  public List<Employee> findAll() {
    return skisExecutor.selectFrom(EMPLOYEE).orderBy(EMPLOYEE.id().asc()).fetchList();
  }

  @Override
  public Page<Employee> findPage(int pageIndex, int pageSize) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .orderBy(EMPLOYEE.id().asc())
        .fetchPage(PageRequest.page(pageIndex, pageSize));
  }

  @Override
  public List<Employee> findByNamePattern(String pattern) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .where(EMPLOYEE.name().like(pattern))
        .orderBy(EMPLOYEE.id().asc())
        .fetchList();
  }

  @Override
  public List<Employee> findByDepartmentId(long departmentId) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .where(EMPLOYEE.departmentId().eq(departmentId))
        .orderBy(EMPLOYEE.id().asc())
        .fetchList();
  }

  @Override
  public Page<Employee> findPageByDepartmentId(long departmentId, int pageIndex, int pageSize) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .where(EMPLOYEE.departmentId().eq(departmentId))
        .orderBy(EMPLOYEE.id().asc())
        .fetchPage(PageRequest.page(pageIndex, pageSize));
  }

  @Override
  public Optional<EmployeeDepartmentView> findDepartmentView(long employeeId) {
    return skisExecutor
        .select(
            EmployeeDepartmentViewPro.of(
                EMPLOYEE.id(),
                EMPLOYEE.code(),
                EMPLOYEE.name(),
                DEPARTMENT.id(),
                DEPARTMENT.name()))
        .from(EMPLOYEE)
        .innerJoin(DEPARTMENT)
        .on(EMPLOYEE.departmentId().eq(DEPARTMENT.id()))
        .where(EMPLOYEE.id().eq(employeeId))
        .fetchOne()
        .toOptional();
  }

  @Override
  public List<EmployeeDepartmentView> findDepartmentViews(long departmentId) {
    return skisExecutor
        .select(
            EmployeeDepartmentViewPro.of(
                EMPLOYEE.id(),
                EMPLOYEE.code(),
                EMPLOYEE.name(),
                DEPARTMENT.id(),
                DEPARTMENT.name()))
        .from(EMPLOYEE)
        .innerJoin(DEPARTMENT)
        .on(EMPLOYEE.departmentId().eq(DEPARTMENT.id()))
        .where(DEPARTMENT.id().eq(departmentId))
        .orderBy(EMPLOYEE.id().asc())
        .fetchList();
  }

  @Override
  public List<Employee> findByProjectId(long projectId) {
    return skisExecutor
        .selectFrom(EMPLOYEE)
        .innerJoin(LINK)
        .on(EMPLOYEE.id().eq(LINK.employeeId()))
        .where(LINK.projectId().eq(projectId))
        .distinct()
        .orderBy(EMPLOYEE.id().asc())
        .fetchList();
  }

  @Override
  public List<EmployeeProjectView> findProjectViews(long employeeId) {
    return skisExecutor
        .select(
            EmployeeProjectViewPro.of(
                EMPLOYEE.id(), EMPLOYEE.name(), PROJECT.id(), PROJECT.name(), PROJECT.status()))
        .from(EMPLOYEE)
        .innerJoin(LINK)
        .on(EMPLOYEE.id().eq(LINK.employeeId()))
        .innerJoin(PROJECT)
        .on(LINK.projectId().eq(PROJECT.id()))
        .where(EMPLOYEE.id().eq(employeeId))
        .orderBy(PROJECT.id().asc())
        .fetchList();
  }

  private static void prepareForInsert(Employee employee) {
    Objects.requireNonNull(employee, "employee");
    Instant now = Instant.now();
    employee.setId(TestModelIds.next());
    employee.setCreateStamp(now);
    employee.setModifyStamp(now);
    employee.setVersion(0L);
  }

  private static EmployeeProjectLink newLink(Long employeeId, Long projectId) {
    EmployeeProjectLink link = new EmployeeProjectLink();
    Instant now = Instant.now();
    link.setId(TestModelIds.next());
    link.setCreateStamp(now);
    link.setModifyStamp(now);
    link.setVersion(0L);
    link.setEmployeeId(Objects.requireNonNull(employeeId, "employeeId"));
    link.setProjectId(Objects.requireNonNull(projectId, "projectId"));
    return link;
  }
}
