package io.skis.benchmark.mybatisplus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Standalone MyBatis-Plus implementation of the benchmark query. */
public final class MybatisPlusUserRepository {

  private final SqlSessionFactory sqlSessionFactory;

  public MybatisPlusUserRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    Environment environment =
        new Environment("skis-benchmark-mybatis-plus", new JdbcTransactionFactory(), dataSource);
    MybatisConfiguration configuration = new MybatisConfiguration(environment);
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.addMapper(MybatisPlusUserMapper.class);
    this.sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
  }

  public MybatisPlusUser findById(Long id) {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.getMapper(MybatisPlusUserMapper.class).selectById(id);
    }
  }
}
