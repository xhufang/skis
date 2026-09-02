package io.skis.mapping;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Decodes the current row of a JDBC result set. */
@FunctionalInterface
public interface RowDecoder<R> {

  /** Decodes the current result-set row without advancing the cursor, preserving SQL NULL. */
  @Nullable R decode(ResultSet resultSet, RowReadContext context) throws SQLException;
}
