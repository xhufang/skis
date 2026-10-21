package io.skis.entity;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * @author: Hu Xin
 */
@SkisEntity
@Table(name = "CL_EMPLOYEE")
public class Employee {

  @Id private @Nullable Long id;

  @Column(nullable = false)
  private @Nullable Instant createStamp;

  @Column(nullable = false)
  private @Nullable Instant modifyStamp;

  @Version private @Nullable Long version;

  @Column(nullable = false)
  private @Nullable String code;

  @Column(nullable = false)
  private @Nullable String name;

  @Column(nullable = false)
  private @Nullable Integer gender;

  @Column(nullable = false)
  private @Nullable String pin;

  @Column(nullable = false)
  private @Nullable String phone;

  @Column(nullable = false)
  private @Nullable String email;

  @Column(nullable = false)
  private @Nullable Instant entryStamp;

  @Column(nullable = false)
  private @Nullable String jobTitle;

  @Column(nullable = false)
  private @Nullable Long departmentId;

  public @Nullable Long getId() {
    return id;
  }

  public void setId(@Nullable Long id) {
    this.id = id;
  }

  public @Nullable Instant getCreateStamp() {
    return createStamp;
  }

  public void setCreateStamp(@Nullable Instant createStamp) {
    this.createStamp = createStamp;
  }

  public @Nullable Instant getModifyStamp() {
    return modifyStamp;
  }

  public void setModifyStamp(@Nullable Instant modifyStamp) {
    this.modifyStamp = modifyStamp;
  }

  public @Nullable Long getVersion() {
    return version;
  }

  public void setVersion(@Nullable Long version) {
    this.version = version;
  }

  public @Nullable String getCode() {
    return code;
  }

  public void setCode(@Nullable String code) {
    this.code = code;
  }

  public @Nullable String getName() {
    return name;
  }

  public void setName(@Nullable String name) {
    this.name = name;
  }

  public @Nullable Integer getGender() {
    return gender;
  }

  public void setGender(@Nullable Integer gender) {
    this.gender = gender;
  }

  public @Nullable String getPin() {
    return pin;
  }

  public void setPin(@Nullable String pin) {
    this.pin = pin;
  }

  public @Nullable String getPhone() {
    return phone;
  }

  public void setPhone(@Nullable String phone) {
    this.phone = phone;
  }

  public @Nullable String getEmail() {
    return email;
  }

  public void setEmail(@Nullable String email) {
    this.email = email;
  }

  public @Nullable Instant getEntryStamp() {
    return entryStamp;
  }

  public void setEntryStamp(@Nullable Instant entryStamp) {
    this.entryStamp = entryStamp;
  }

  public @Nullable String getJobTitle() {
    return jobTitle;
  }

  public void setJobTitle(@Nullable String jobTitle) {
    this.jobTitle = jobTitle;
  }

  public @Nullable Long getDepartmentId() {
    return departmentId;
  }

  public void setDepartmentId(@Nullable Long departmentId) {
    this.departmentId = departmentId;
  }
}
