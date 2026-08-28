package io.skis.benchmark.jooq;

import java.time.Instant;

/** Result shape used by the jOOQ benchmark. */
public final class JooqUser {

  private Long id;
  private String username;
  private String password;
  private Instant createStamp;
  private Instant modifyStamp;
  private String sex;
  private Instant birthday;
  private Boolean deleted;
  private Long version;

  public JooqUser() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
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

  public String getSex() {
    return sex;
  }

  public void setSex(String sex) {
    this.sex = sex;
  }

  public Instant getBirthday() {
    return birthday;
  }

  public void setBirthday(Instant birthday) {
    this.birthday = birthday;
  }

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean deleted) {
    this.deleted = deleted;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }
}
