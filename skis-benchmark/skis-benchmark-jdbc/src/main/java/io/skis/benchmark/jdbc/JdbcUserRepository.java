package io.skis.benchmark.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/** Hand-written JDBC-by-index implementation used as the performance baseline. */
public final class JdbcUserRepository {

  private static final String FIND_BY_ID_SQL =
      """
      SELECT id, username, password, create_stamp, modify_stamp, sex, birthday, deleted, version
      FROM skis_user
      WHERE id = ?
      """;

  private final DataSource dataSource;

  public JdbcUserRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public JdbcUser findById(Long id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(FIND_BY_ID_SQL)) {
      statement.setLong(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? readUser(resultSet) : null;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("JDBC benchmark query failed", exception);
    }
  }

  private static JdbcUser readUser(ResultSet resultSet) throws SQLException {
    JdbcUser user = new JdbcUser();
    user.setId(resultSet.getLong(1));
    user.setUsername(resultSet.getString(2));
    user.setPassword(resultSet.getString(3));
    user.setCreateStamp(toInstant(resultSet.getTimestamp(4)));
    user.setModifyStamp(toInstant(resultSet.getTimestamp(5)));
    user.setSex(resultSet.getString(6));
    user.setBirthday(toInstant(resultSet.getTimestamp(7)));
    user.setDeleted(resultSet.getBoolean(8));
    user.setVersion(resultSet.getLong(9));
    return user;
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
