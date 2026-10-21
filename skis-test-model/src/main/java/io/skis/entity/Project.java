package io.skis.entity;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * @author: Hu Xin
 */
@SkisEntity
@Table(name = "CL_PROJECT")
public class Project {

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
  private @Nullable BigDecimal budget;

  private @Nullable Instant startStamp;

  private @Nullable Instant endStamp;

  @Column(nullable = false)
  private @Nullable String status;

  private @Nullable String description;

  private @Nullable Long principalId;

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

  public @Nullable BigDecimal getBudget() {
    return budget;
  }

  public void setBudget(@Nullable BigDecimal budget) {
    this.budget = budget;
  }

  public @Nullable Instant getStartStamp() {
    return startStamp;
  }

  public void setStartStamp(@Nullable Instant startStamp) {
    this.startStamp = startStamp;
  }

  public @Nullable Instant getEndStamp() {
    return endStamp;
  }

  public void setEndStamp(@Nullable Instant endStamp) {
    this.endStamp = endStamp;
  }

  public @Nullable String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  public @Nullable String getDescription() {
    return description;
  }

  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public @Nullable Long getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(@Nullable Long principalId) {
    this.principalId = principalId;
  }
}
