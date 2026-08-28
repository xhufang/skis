package io.skis.benchmark.jimmer;

import java.time.Instant;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.Table;
import org.babyfish.jimmer.sql.Version;
import org.jspecify.annotations.Nullable;

/** Jimmer entity mapped to the shared benchmark table. */
@Entity
@Table(name = "skis_user")
public interface JimmerUser {

  @Id
  long id();

  String username();

  String password();

  Instant createStamp();

  Instant modifyStamp();

  @Nullable String sex();

  @Nullable Instant birthday();

  boolean deleted();

  @Version
  long version();
}
