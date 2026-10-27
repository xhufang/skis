package io.skis.integration.ids;

import io.skis.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@SkisEntity
@Table(name = "rejectingidrecord")
public record RejectingIdRecord(
    @Id(strategy = IdStrategy.IDENTITY) @Nullable Long id) {
  public RejectingIdRecord {
    if (id != null) { throw new IllegalStateException("constructor failure"); }
  }
}

