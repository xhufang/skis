package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "uuidstringidbean")
public class UuidStringIdBean {
  @Id(strategy = IdStrategy.UUID)
  @Column(name = "Generated Key", nullable = false)
  private @Nullable String id;
  @Column(nullable = false)
  private String name = "new";
  @Transient public int reads;
  @Transient public int writes;
  @Transient public boolean failRead;
  @Transient public boolean failApply;

  public UuidStringIdBean() {}

  public @Nullable String getId() {
    reads++;
    if (failRead) { throw new IllegalStateException("reader failure"); }
    return id;
  }

  public void setId(@Nullable String id) {
    writes++;
    if (failApply) { throw new IllegalStateException("setter failure"); }
    this.id = id;
  }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
}

