package io.skis.service;

import io.skis.entity.Project;
import io.skis.projection.EmployeeProjectView;
import io.skis.projection.ProjectPrincipalView;
import io.skis.query.Page;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * @author: Hu Xin
 */
public interface ProjectService {

  Project createProject(Project project);

  int updateProject(Project project);

  int deleteProject(long id);

  Optional<Project> findById(long id);

  Optional<Project> findOneByCode(String code);

  List<Project> findAll();

  List<Project> findByStatus(String status);

  List<Project> findByBudgetRange(BigDecimal minimum, BigDecimal maximum);

  Page<Project> findPageByStatus(String status, int pageIndex, int pageSize);

  List<Project> findByEmployeeId(long employeeId);

  Optional<ProjectPrincipalView> findPrincipalView(long projectId);

  List<ProjectPrincipalView> findPrincipalViews();

  List<EmployeeProjectView> findMemberViews(long projectId);
}
