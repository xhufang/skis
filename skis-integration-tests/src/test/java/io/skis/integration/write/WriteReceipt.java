package io.skis.integration.write;

import io.skis.annotations.PropertyFrom;
import io.skis.annotations.SpecialProjection;
import io.skis.integration.write.skis.WriteRowTable;
import org.jspecify.annotations.NullMarked;

@NullMarked
@SpecialProjection
public record WriteReceipt(@PropertyFrom(WriteRowTable.ID) Long id,
    @PropertyFrom(WriteRowTable.NAME) String name) {}
