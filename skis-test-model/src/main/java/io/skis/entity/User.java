package io.skis.entity;

import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@SkisEntity
@Table(name = "X_USER")
public class User {

  @Id private @Nullable Long id;

  private @Nullable Instant createStamp;

  private @Nullable Instant modifyStamp;

  @Version private @Nullable Long version;

  private @Nullable String username;

  private @Nullable String password;

  private @Nullable String phone;

  private @Nullable String email;

  private @Nullable Instant birthday;

  private @Nullable Integer sex;

  private @Nullable String description;

  private @Nullable Boolean deleted;

  public User() {}

  public User(
      @Nullable Long id,
      @Nullable Instant createStamp,
      @Nullable Instant modifyStamp,
      @Nullable Long version,
      @Nullable String username,
      @Nullable String password,
      @Nullable String phone,
      @Nullable String email,
      @Nullable Instant birthday,
      @Nullable Integer sex,
      @Nullable String description,
      @Nullable Boolean deleted) {
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

  public @Nullable String getUsername() {
    return username;
  }

  public void setUsername(@Nullable String username) {
    this.username = username;
  }

  public @Nullable String getPassword() {
    return password;
  }

  public void setPassword(@Nullable String password) {
    this.password = password;
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

  public @Nullable Instant getBirthday() {
    return birthday;
  }

  public void setBirthday(@Nullable Instant birthday) {
    this.birthday = birthday;
  }

  public @Nullable Integer getSex() {
    return sex;
  }

  public void setSex(@Nullable Integer sex) {
    this.sex = sex;
  }

  public @Nullable String getDescription() {
    return description;
  }

  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public @Nullable Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(@Nullable Boolean deleted) {
    this.deleted = deleted;
  }
}
