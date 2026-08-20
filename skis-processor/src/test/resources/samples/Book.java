package samples;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;

@SkisEntity
@Table(name = "book", schema = "inventory")
public record Book(
    @Id @Column(name = "id", nullable = false) Long id,
    @Column(name = "book_name") String name,
    @Version @Column(name = "version", nullable = false) Long version) {}
