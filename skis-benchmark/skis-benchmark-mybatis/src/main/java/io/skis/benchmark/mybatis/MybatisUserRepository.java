package io.skis.benchmark.mybatis;

import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Standalone MyBatis implementation of the benchmark query. */
public final class MybatisUserRepository {

  private final SqlSessionFactory sqlSessionFactory;

  public MybatisUserRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    Environment environment =
        new Environment("skis-benchmark-mybatis", new JdbcTransactionFactory(), dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.addMapper(MybatisUserMapper.class);
    this.sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  public MybatisUser findById(Long id) {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.getMapper(MybatisUserMapper.class).findById(id);
    }
  }
}
