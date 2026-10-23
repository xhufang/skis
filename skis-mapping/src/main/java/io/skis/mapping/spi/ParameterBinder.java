package io.skis.mapping.spi;

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

  /**
   * Binds once with synchronous observation of existing locals. Generated/framework binders
   * override this separate channel; opaque custom binders are never replayed or proxied.
   *
   * <p>Observing binders check {@link ParameterBindingObserver#needsMore()} before passing values,
   * including primitive boxing. Once it returns false, they may skip further observation and demand
   * checks, but must still complete normal binding and return the first unbound index.
   */
  default int bindObserved(
      PreparedStatement statement,
      int firstIndex,
      P parameters,
      JdbcWriteContext context,
      ParameterBindingObserver observer)
      throws SQLException {
    int nextIndex = bind(statement, firstIndex, parameters, context);
    observer.unavailable();
    return nextIndex;
  }
}
