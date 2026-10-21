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
@Table(name = "CL_DEPARTMENT")
public class Department {

  @Id private @Nullable Long id;

  @Column(nullable = false)
  private @Nullable Instant createStamp;

  @Column(nullable = false)
  private @Nullable Instant modifyStamp;

  @Version private @Nullable Long version;

  @Column(nullable = false)
  private @Nullable String name;

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

  public @Nullable String getName() {
    return name;
  }

  public void setName(@Nullable String name) {
    this.name = name;
  }
}
