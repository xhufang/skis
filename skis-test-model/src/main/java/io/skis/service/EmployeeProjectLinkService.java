package io.skis.service;

import io.skis.entity.EmployeeProjectLink;
import java.util.List;
import java.util.Optional;

/** Employee/project relationship operations backed by the link entity. */
public interface EmployeeProjectLinkService {

  EmployeeProjectLink createLink(EmployeeProjectLink link);

  EmployeeProjectLink linkEmployeeToProject(long employeeId, long projectId);

  int updateLink(EmployeeProjectLink link);

  int deleteLink(long id);

  int unlinkEmployeeFromProject(long employeeId, long projectId);

  Optional<EmployeeProjectLink> findById(long id);

  Optional<EmployeeProjectLink> findOne(long employeeId, long projectId);

  List<EmployeeProjectLink> findByEmployeeId(long employeeId);

  List<EmployeeProjectLink> findByProjectId(long projectId);
}
