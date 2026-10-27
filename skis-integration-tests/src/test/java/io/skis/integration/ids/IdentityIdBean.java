package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "identityidbean")
public class IdentityIdBean {
  @Id(strategy = IdStrategy.IDENTITY)
  @Column(name = "Generated Key", nullable = false)
  private @Nullable Long id;
  @Column(nullable = false)
  private String name = "new";
  @Transient public int reads;
  @Transient public int writes;
  @Transient public boolean failRead;
  @Transient public boolean failApply;

  public IdentityIdBean() {}

  public @Nullable Long getId() {
    reads++;
    if (failRead) { throw new IllegalStateException("reader failure"); }
    return id;
  }

  public void setId(@Nullable Long id) {
    writes++;
    if (failApply) { throw new IllegalStateException("setter failure"); }
    this.id = id;
  }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
}

