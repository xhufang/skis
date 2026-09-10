package io.skis.entity;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.time.Instant;

/**
 * @author: Hu Xin
 */
@SkisEntity
@Table(name = "CL_EMPLOYEE_PROJECT_LINK")
public class EmployeeProjectLink {

  @Id private Long id;

  @Column(nullable = false)
  private Instant createStamp;

  @Column(nullable = false)
  private Instant modifyStamp;

  @Version private Long version;

  @Column(nullable = false)
  private Long employeeId;

  @Column(nullable = false)
  private Long projectId;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Instant getCreateStamp() {
    return createStamp;
  }

  public void setCreateStamp(Instant createStamp) {
    this.createStamp = createStamp;
  }

  public Instant getModifyStamp() {
    return modifyStamp;
  }

  public void setModifyStamp(Instant modifyStamp) {
    this.modifyStamp = modifyStamp;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public Long getEmployeeId() {
    return employeeId;
  }

  public void setEmployeeId(Long employeeId) {
    this.employeeId = employeeId;
  }

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }
}
