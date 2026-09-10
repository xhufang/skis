package io.skis.service;

import io.skis.core.id.IdWorker;
import io.skis.entity.Employee;
import io.skis.entity.EmployeeProjectLink;
import io.skis.entity.skis.DepartmentTable;
import io.skis.entity.skis.EmployeeMeta;
import io.skis.entity.skis.EmployeeProjectLinkMeta;
import io.skis.entity.skis.EmployeeProjectLinkTable;
import io.skis.entity.skis.EmployeeTable;
import io.skis.entity.skis.ProjectTable;
import io.skis.projection.EmployeeDepartmentView;
import io.skis.projection.EmployeeProjectView;
import io.skis.projection.skis.EmployeeDepartmentViewProjection;
import io.skis.projection.skis.EmployeeProjectViewProjection;
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

  private static final DepartmentTable DEPARTMENT = DepartmentTable.DEPARTMENT;
  private static final EmployeeTable EMPLOYEE = EmployeeTable.EMPLOYEE;
  private static final EmployeeProjectLinkTable LINK =
      EmployeeProjectLinkTable.EMPLOYEE_PROJECT_LINK;
  private static final ProjectTable PROJECT = ProjectTable.PROJECT;

  private final SkisExecutor skisExecutor;

  public EmployeeServiceImpl(SkisExecutor skisExecutor) {
    this.skisExecutor = Objects.requireNonNull(skisExecutor, "skisExecutor");
  }

  @Override
  public Employee createEmployee(Employee employee) {
    prepareForInsert(employee);
    skisExecutor.insert(EmployeeMeta.ENTITY, employee);
    return employee;
  }

  @Override
  public Employee createEmployeeWithProjects(Employee employee, List<Long> projectIds) {
    Objects.requireNonNull(projectIds, "projectIds");
    List<Long> distinctProjectIds = List.copyOf(new LinkedHashSet<>(projectIds));
    prepareForInsert(employee);

    return skisExecutor.inTransaction(
        session -> {
          session.insert(EmployeeMeta.ENTITY, employee);
          for (Long projectId : distinctProjectIds) {
            session.insert(EmployeeProjectLinkMeta.ENTITY, newLink(employee.getId(), projectId));
          }
          return employee;
        });
  }

  @Override
  public int updateEmployee(Employee employee) {
    Objects.requireNonNull(employee, "employee");
    employee.setModifyStamp(Instant.now());
    return skisExecutor.updateById(EmployeeMeta.ENTITY, employee);
  }

  @Override
  public int deleteEmployee(long id) {
    return skisExecutor.deleteById(EmployeeMeta.ENTITY, id);
  }

  @Override
  public Optional<Employee> findById(long id) {
    return skisExecutor.findById(EmployeeMeta.ENTITY, id);
  }

  @Override
  public Optional<Employee> findOneByCode(String code) {
    return skisExecutor.selectFrom(EMPLOYEE).where(EMPLOYEE.code().eq(code)).fetchOne();
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
            EmployeeDepartmentViewProjection.of(
                EMPLOYEE.id(),
                EMPLOYEE.code(),
                EMPLOYEE.name(),
                DEPARTMENT.id(),
                DEPARTMENT.name()))
        .from(EMPLOYEE)
        .innerJoin(DEPARTMENT)
        .on(EMPLOYEE.departmentId().eq(DEPARTMENT.id()))
        .where(EMPLOYEE.id().eq(employeeId))
        .fetchOne();
  }

  @Override
  public List<EmployeeDepartmentView> findDepartmentViews(long departmentId) {
    return skisExecutor
        .select(
            EmployeeDepartmentViewProjection.of(
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
            EmployeeProjectViewProjection.of(
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
    employee.setId(IdWorker.getInstance().nextId());
    employee.setCreateStamp(now);
    employee.setModifyStamp(now);
    employee.setVersion(0L);
  }

  private static EmployeeProjectLink newLink(Long employeeId, Long projectId) {
    EmployeeProjectLink link = new EmployeeProjectLink();
    Instant now = Instant.now();
    link.setId(IdWorker.getInstance().nextId());
    link.setCreateStamp(now);
    link.setModifyStamp(now);
    link.setVersion(0L);
    link.setEmployeeId(Objects.requireNonNull(employeeId, "employeeId"));
    link.setProjectId(Objects.requireNonNull(projectId, "projectId"));
    return link;
  }
}
