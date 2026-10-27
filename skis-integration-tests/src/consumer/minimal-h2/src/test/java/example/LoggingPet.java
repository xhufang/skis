package example;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** Test entity distinguishing the facade's key validation from one read per generated binder slot. */
@SkisEntity
@Table(name = "logging_pet")
public record LoggingPet(
    @Id @Column(nullable = false) Long id,
    @Column(nullable = false) String secret,
    @Version @Column(nullable = false) @Nullable Long version) {
  static final AtomicInteger IDS = new AtomicInteger();
  static final AtomicInteger SECRETS = new AtomicInteger();
  static final AtomicInteger VERSIONS = new AtomicInteger();

  @Override public Long id() { IDS.incrementAndGet(); return id; }
  @Override public String secret() { SECRETS.incrementAndGet(); return secret; }
  @Override public @Nullable Long version() { VERSIONS.incrementAndGet(); return version; }

  static void resetReads() { IDS.set(0); SECRETS.set(0); VERSIONS.set(0); }
  static List<Integer> reads() { return List.of(IDS.get(), SECRETS.get(), VERSIONS.get()); }
}
