package io.skis.service;

import io.skis.entity.Employee;
import io.skis.projection.EmployeeDepartmentView;
import io.skis.projection.EmployeeProjectView;
import io.skis.query.Page;
import java.util.List;
import java.util.Optional;

/**
 * @author: Hu Xin
 */
public interface EmployeeService {

  Employee createEmployee(Employee employee);

  Employee createEmployeeWithProjects(Employee employee, List<Long> projectIds);

  int updateEmployee(Employee employee);

  int deleteEmployee(long id);

  Optional<Employee> findById(long id);

  Optional<Employee> findOneByCode(String code);

  List<Employee> findAll();

  Page<Employee> findPage(int pageIndex, int pageSize);

  List<Employee> findByNamePattern(String pattern);

  List<Employee> findByDepartmentId(long departmentId);

  Page<Employee> findPageByDepartmentId(long departmentId, int pageIndex, int pageSize);

  Optional<EmployeeDepartmentView> findDepartmentView(long employeeId);

  List<EmployeeDepartmentView> findDepartmentViews(long departmentId);

  List<Employee> findByProjectId(long projectId);

  List<EmployeeProjectView> findProjectViews(long employeeId);
}
