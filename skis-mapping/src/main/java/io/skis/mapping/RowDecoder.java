package io.skis.mapping;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Decodes the current row of a JDBC result set; {@code R} carries whether SQL NULL is allowed. */
@FunctionalInterface
public interface RowDecoder<R extends @Nullable Object> {

  /** Decodes the current result-set row without advancing the cursor. */
  R decode(ResultSet resultSet, RowReadContext context) throws SQLException;
}
