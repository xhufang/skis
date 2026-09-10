package io.skis.service;

import io.skis.core.id.IdWorker;
import io.skis.entity.Project;
import io.skis.entity.skis.EmployeeProjectLinkTable;
import io.skis.entity.skis.EmployeeTable;
import io.skis.entity.skis.ProjectMeta;
import io.skis.entity.skis.ProjectTable;
import io.skis.projection.EmployeeProjectView;
import io.skis.projection.ProjectPrincipalView;
import io.skis.projection.skis.EmployeeProjectViewProjection;
import io.skis.projection.skis.ProjectPrincipalViewProjection;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.SelectQuery;
import io.skis.runtime.SkisExecutor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Project CRUD, filtering, relationship, and LEFT JOIN projection examples. */
public class ProjectServiceImpl implements ProjectService {

  private static final EmployeeTable EMPLOYEE = EmployeeTable.EMPLOYEE;
  private static final EmployeeProjectLinkTable LINK =
      EmployeeProjectLinkTable.EMPLOYEE_PROJECT_LINK;
  private static final ProjectTable PROJECT = ProjectTable.PROJECT;

  private final SkisExecutor skisExecutor;

  public ProjectServiceImpl(SkisExecutor skisExecutor) {
    this.skisExecutor = Objects.requireNonNull(skisExecutor, "skisExecutor");
  }

  @Override
  public Project createProject(Project project) {
    Objects.requireNonNull(project, "project");
    Instant now = Instant.now();
    project.setId(IdWorker.getInstance().nextId());
    project.setCreateStamp(now);
    project.setModifyStamp(now);
    project.setVersion(0L);
    skisExecutor.insert(ProjectMeta.ENTITY, project);
    return project;
  }

  @Override
  public int updateProject(Project project) {
    Objects.requireNonNull(project, "project");
    project.setModifyStamp(Instant.now());
    return skisExecutor.updateById(ProjectMeta.ENTITY, project);
  }

  @Override
  public int deleteProject(long id) {
    return skisExecutor.deleteById(ProjectMeta.ENTITY, id);
  }

  @Override
  public Optional<Project> findById(long id) {
    return skisExecutor.findById(ProjectMeta.ENTITY, id);
  }

  @Override
  public Optional<Project> findOneByCode(String code) {
    return skisExecutor.selectFrom(PROJECT).where(PROJECT.code().eq(code)).fetchOne();
  }

  @Override
  public List<Project> findAll() {
    return skisExecutor.selectFrom(PROJECT).orderBy(PROJECT.id().asc()).fetchList();
  }

  @Override
  public List<Project> findByStatus(String status) {
    return skisExecutor
        .selectFrom(PROJECT)
        .where(PROJECT.status().eq(status))
        .orderBy(PROJECT.id().asc())
        .fetchList();
  }

  @Override
  public List<Project> findByBudgetRange(BigDecimal minimum, BigDecimal maximum) {
    return skisExecutor
        .selectFrom(PROJECT)
        .where(PROJECT.budget().between(minimum, maximum))
        .orderBy(PROJECT.budget().asc(), PROJECT.id().asc())
        .fetchList();
  }

  @Override
  public Page<Project> findPageByStatus(String status, int pageIndex, int pageSize) {
    return skisExecutor
        .selectFrom(PROJECT)
        .where(PROJECT.status().eq(status))
        .orderBy(PROJECT.id().asc())
        .fetchPage(PageRequest.page(pageIndex, pageSize));
  }

  @Override
  public List<Project> findByEmployeeId(long employeeId) {
    return skisExecutor
        .selectFrom(PROJECT)
        .innerJoin(LINK)
        .on(PROJECT.id().eq(LINK.projectId()))
        .where(LINK.employeeId().eq(employeeId))
        .distinct()
        .orderBy(PROJECT.id().asc())
        .fetchList();
  }

  @Override
  public Optional<ProjectPrincipalView> findPrincipalView(long projectId) {
    return principalQuery().where(PROJECT.id().eq(projectId)).fetchOne();
  }

  @Override
  public List<ProjectPrincipalView> findPrincipalViews() {
    return principalQuery().orderBy(PROJECT.id().asc()).fetchList();
  }

  @Override
  public List<EmployeeProjectView> findMemberViews(long projectId) {
    return skisExecutor
        .select(
            EmployeeProjectViewProjection.of(
                EMPLOYEE.id(), EMPLOYEE.name(), PROJECT.id(), PROJECT.name(), PROJECT.status()))
        .from(PROJECT)
        .innerJoin(LINK)
        .on(PROJECT.id().eq(LINK.projectId()))
        .innerJoin(EMPLOYEE)
        .on(LINK.employeeId().eq(EMPLOYEE.id()))
        .where(PROJECT.id().eq(projectId))
        .orderBy(EMPLOYEE.modifyStamp().desc())
        .fetch();
  }

  private SelectQuery<Project, ProjectPrincipalView> principalQuery() {
    return skisExecutor
        .select(
            ProjectPrincipalViewProjection.of(
                PROJECT.id(), PROJECT.name(), EMPLOYEE.id(), EMPLOYEE.name()))
        .from(PROJECT)
        .leftJoin(EMPLOYEE)
        .on(PROJECT.principalId().eq(EMPLOYEE.id()));
  }
}
