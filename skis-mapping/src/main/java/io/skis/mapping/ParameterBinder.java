package io.skis.mapping;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Binds a typed parameter object to a prepared statement. */
@FunctionalInterface
public interface ParameterBinder<P> {

  /**
   * Binds parameters beginning at {@code firstIndex}.
   *
   * @return the first unbound JDBC parameter index
   */
  int bind(PreparedStatement statement, int firstIndex, P parameters, JdbcWriteContext context)
      throws SQLException;
}
