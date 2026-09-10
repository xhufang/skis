package io.skis.service;

import io.skis.entity.Department;
import io.skis.entity.Employee;
import io.skis.query.Page;
import java.util.List;
import java.util.Optional;

/**
 * @author: Hu Xin
 */
public interface DepartmentService {

  Department createDepartment(Department department);

  int updateDepartment(Department department);

  int deleteDepartment(long id);

  Optional<Department> findById(long id);

  List<Department> findAll();

  List<Department> findByNamePattern(String pattern);

  List<Employee> findEmployees(long departmentId);

  Page<Employee> findEmployees(long departmentId, int pageIndex, int pageSize);
}
