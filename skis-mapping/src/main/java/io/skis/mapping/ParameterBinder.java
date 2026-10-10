package io.skis.mapping;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Binds a typed parameter object to a prepared statement. */
@FunctionalInterface
public interface ParameterBinder<P> {

  /**
   * Binds parameters beginning at {@code firstIndex}.
   *
   * <p>The parameter object is invocation-scoped input. Implementations must not retain it after
   * this method returns or place it in shared scratch storage. Any scratch storage must belong to
   * this invocation, or to an explicitly non-thread-safe session, and must release object
   * references on an exceptional exit.
   *
   * @return the first unbound JDBC parameter index
   */
  int bind(PreparedStatement statement, int firstIndex, P parameters, JdbcWriteContext context)
      throws SQLException;
}
