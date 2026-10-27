package example;

import io.skis.annotations.Id;
import io.skis.annotations.IdStrategy;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;

@SkisEntity
@Table(name = "generated_ticket")
public record GeneratedTicket(@Id(strategy = IdStrategy.UUID) java.util.UUID id, String name) {}

