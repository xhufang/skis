package io.skis.service;

import io.skis.core.id.IdWorker;
import io.skis.entity.EmployeeProjectLink;
import io.skis.entity.skis.EmployeeProjectLinkMeta;
import io.skis.entity.skis.EmployeeProjectLinkTable;
import io.skis.runtime.SkisExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** CRUD and lookup examples for the explicit employee/project link entity. */
public class EmployeeProjectLinkServiceImpl implements EmployeeProjectLinkService {

  private static final EmployeeProjectLinkTable LINK =
      EmployeeProjectLinkTable.EMPLOYEE_PROJECT_LINK;

  private final SkisExecutor skisExecutor;

  public EmployeeProjectLinkServiceImpl(SkisExecutor skisExecutor) {
    this.skisExecutor = Objects.requireNonNull(skisExecutor, "skisExecutor");
  }

  @Override
  public EmployeeProjectLink createLink(EmployeeProjectLink link) {
    prepareForInsert(link);
    skisExecutor.insert(EmployeeProjectLinkMeta.ENTITY, link);
    return link;
  }

  @Override
  public EmployeeProjectLink linkEmployeeToProject(long employeeId, long projectId) {
    EmployeeProjectLink link = new EmployeeProjectLink();
    link.setEmployeeId(employeeId);
    link.setProjectId(projectId);
    return createLink(link);
  }

  @Override
  public int updateLink(EmployeeProjectLink link) {
    Objects.requireNonNull(link, "link");
    link.setModifyStamp(Instant.now());
    return skisExecutor.updateById(EmployeeProjectLinkMeta.ENTITY, link);
  }

  @Override
  public int deleteLink(long id) {
    return skisExecutor.deleteById(EmployeeProjectLinkMeta.ENTITY, id);
  }

  @Override
  public int unlinkEmployeeFromProject(long employeeId, long projectId) {
    return skisExecutor.inTransaction(
        session -> {
          Optional<EmployeeProjectLink> link =
              session
                  .selectFrom(LINK)
                  .where(LINK.employeeId().eq(employeeId))
                  .and(LINK.projectId().eq(projectId))
                  .fetchOne();
          return link
              .map(value -> session.deleteById(EmployeeProjectLinkMeta.ENTITY, value.getId()))
              .orElse(0);
        });
  }

  @Override
  public Optional<EmployeeProjectLink> findById(long id) {
    return skisExecutor.findById(EmployeeProjectLinkMeta.ENTITY, id);
  }

  @Override
  public Optional<EmployeeProjectLink> findOne(long employeeId, long projectId) {
    return skisExecutor
        .selectFrom(LINK)
        .where(LINK.employeeId().eq(employeeId))
        .and(LINK.projectId().eq(projectId))
        .fetchOne();
  }

  @Override
  public List<EmployeeProjectLink> findByEmployeeId(long employeeId) {
    return skisExecutor
        .selectFrom(LINK)
        .where(LINK.employeeId().eq(employeeId))
        .orderBy(LINK.projectId().asc(), LINK.id().asc())
        .fetchList();
  }

  @Override
  public List<EmployeeProjectLink> findByProjectId(long projectId) {
    return skisExecutor
        .selectFrom(LINK)
        .where(LINK.projectId().eq(projectId))
        .orderBy(LINK.employeeId().asc(), LINK.id().asc())
        .fetchList();
  }

  private static void prepareForInsert(EmployeeProjectLink link) {
    Objects.requireNonNull(link, "link");
    Instant now = Instant.now();
    link.setId(IdWorker.getInstance().nextId());
    link.setCreateStamp(now);
    link.setModifyStamp(now);
    link.setVersion(0L);
  }
}
