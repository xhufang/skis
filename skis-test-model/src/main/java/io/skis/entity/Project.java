package io.skis.entity;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * @author: Hu Xin
 */
@SkisEntity
@Table(name = "CL_PROJECT")
public class Project {

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
  private BigDecimal budget;

  private Instant startStamp;

  private Instant endStamp;

  @Column(nullable = false)
  private String status;

  private String description;

  private Long principalId;

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

  public BigDecimal getBudget() {
    return budget;
  }

  public void setBudget(BigDecimal budget) {
    this.budget = budget;
  }

  public Instant getStartStamp() {
    return startStamp;
  }

  public void setStartStamp(Instant startStamp) {
    this.startStamp = startStamp;
  }

  public Instant getEndStamp() {
    return endStamp;
  }

  public void setEndStamp(Instant endStamp) {
    this.endStamp = endStamp;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Long getPrincipalId() {
    return principalId;
  }

  public void setPrincipalId(Long principalId) {
    this.principalId = principalId;
  }
}
