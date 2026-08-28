package io.skis.benchmark.mybatis;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Annotation mapper for the MyBatis baseline. */
public interface MybatisUserMapper {

  @Select(
      """
      SELECT id, username, password, create_stamp, modify_stamp, sex, birthday, deleted, version
      FROM skis_user
      WHERE id = #{id}
      """)
  MybatisUser findById(@Param("id") Long id);
}
