package io.skis.entity;

import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.time.Instant;

@SkisEntity
@Table(name = "X_USER")
public class User {

  @Id private Long id;

  private Instant createStamp;

  private Instant modifyStamp;

  @Version private Long version;

  private String username;

  private String password;

  private String phone;

  private String email;

  private Instant birthday;

  private Integer sex;

  private String description;

  private Boolean deleted;

  public User() {}

  public User(
      Long id,
      Instant createStamp,
      Instant modifyStamp,
      Long version,
      String username,
      String password,
      String phone,
      String email,
      Instant birthday,
      Integer sex,
      String description,
      Boolean deleted) {
    this.id = id;
    this.createStamp = createStamp;
    this.modifyStamp = modifyStamp;
    this.version = version;
    this.username = username;
    this.password = password;
    this.phone = phone;
    this.email = email;
    this.birthday = birthday;
    this.sex = sex;
    this.description = description;
    this.deleted = deleted;
  }

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

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
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

  public Instant getBirthday() {
    return birthday;
  }

  public void setBirthday(Instant birthday) {
    this.birthday = birthday;
  }

  public Integer getSex() {
    return sex;
  }

  public void setSex(Integer sex) {
    this.sex = sex;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean deleted) {
    this.deleted = deleted;
  }
}
