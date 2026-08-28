package io.skis.benchmark.mybatisflex;

import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.core.mybatis.FlexSqlSessionFactoryBuilder;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Standalone MyBatis-Flex implementation of the benchmark query. */
public final class MybatisFlexUserRepository {

  private final SqlSessionFactory sqlSessionFactory;

  public MybatisFlexUserRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    Environment environment =
        new Environment(
            "skis-benchmark-mybatis-flex",
            new JdbcTransactionFactory(),
            new FlexDataSource("default", dataSource));
    FlexConfiguration configuration = new FlexConfiguration(environment);
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLogImpl(NoLoggingImpl.class);
    this.sqlSessionFactory = new FlexSqlSessionFactoryBuilder().build(configuration);
    configuration.addMapper(MybatisFlexUserMapper.class);
  }

  public MybatisFlexUser findById(Long id) {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.getMapper(MybatisFlexUserMapper.class).selectOneById(id);
    }
  }
}
