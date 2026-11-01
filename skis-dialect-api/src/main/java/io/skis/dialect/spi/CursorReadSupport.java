package io.skis.dialect.spi;

import java.sql.Connection;
import java.sql.SQLException;

/** Immutable, thread-safe driver policy for genuinely incremental JDBC result reading. */
public interface CursorReadSupport {

  /** Explicitly unsupported; materialized queries remain available. */
  CursorReadSupport UNSUPPORTED =
      new CursorReadSupport() {
        @Override
        public int defaultFetchSize() {
          return 0;
        }

        @Override
        public boolean supportsConnection(Connection connection) {
          return false;
        }
      };

  /** Positive cursor-only default, or zero when incremental reading is unsupported. */
  int defaultFetchSize();

  /**
   * Checks the actual driver and its mode without changing connection state or executing SQL.
   * Transaction, statement shape, and effective fetch-size checks belong to the JDBC kernel.
   */
  boolean supportsConnection(Connection connection) throws SQLException;
}
