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
@Table(name = "CL_EMPLOYEE")
public class Employee {

  @Id private Long id;

  @Column(nullable = false)
  private Instant createStamp;

  @Column(nullable = false)
  private Instant modifyStamp;

  @Version private Long version;

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private Integer gender;

  @Column(nullable = false)
  private String pin;

  @Column(nullable = false)
  private String phone;

  @Column(nullable = false)
  private String email;

  @Column(nullable = false)
  private Instant entryStamp;

  @Column(nullable = false)
  private String jobTitle;

  @Column(nullable = false)
  private Long departmentId;

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

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Integer getGender() {
    return gender;
  }

  public void setGender(Integer gender) {
    this.gender = gender;
  }

  public String getPin() {
    return pin;
  }

  public void setPin(String pin) {
    this.pin = pin;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public Instant getEntryStamp() {
    return entryStamp;
  }

  public void setEntryStamp(Instant entryStamp) {
    this.entryStamp = entryStamp;
  }

  public String getJobTitle() {
    return jobTitle;
  }

  public void setJobTitle(String jobTitle) {
    this.jobTitle = jobTitle;
  }

  public Long getDepartmentId() {
    return departmentId;
  }

  public void setDepartmentId(Long departmentId) {
    this.departmentId = departmentId;
  }
}
