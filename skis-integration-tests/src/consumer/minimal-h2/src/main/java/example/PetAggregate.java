package example;

import io.skis.annotations.ReusableProjection;
import java.math.BigInteger;
import org.jspecify.annotations.Nullable;

@ReusableProjection
public record PetAggregate(@Nullable String name, Long rowCount, @Nullable BigInteger idSum) {}
