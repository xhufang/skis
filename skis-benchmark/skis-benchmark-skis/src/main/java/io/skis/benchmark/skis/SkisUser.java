package io.skis.benchmark.skis;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.time.Instant;

/** SKIS entity mapped to the shared benchmark table. */
@SkisEntity
@Table(name = "skis_user")
public class SkisUser {

  @Id private Long id;

  @Column(nullable = false)
  private String username;

  @Column(nullable = false)
  private String password;

  @Column(nullable = false)
  private Instant createStamp;

  @Column(nullable = false)
  private Instant modifyStamp;

  private String sex;

  private Instant birthday;

  @Column(nullable = false)
  private Boolean deleted;

  @Version private Long version;

  public SkisUser() {}

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
