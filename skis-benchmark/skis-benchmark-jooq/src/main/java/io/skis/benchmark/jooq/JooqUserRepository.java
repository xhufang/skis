package io.skis.benchmark.jooq;

import static org.jooq.conf.RenderQuotedNames.NEVER;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/** Standalone jOOQ DSL implementation of the benchmark query. */
public final class JooqUserRepository {

  private static final Table<?> SKIS_USER = table(name("skis_user"));
  private static final Field<Long> ID = field(name("id"), Long.class);
  private static final Field<String> USERNAME = field(name("username"), String.class);
  private static final Field<String> PASSWORD = field(name("password"), String.class);
  private static final Field<Timestamp> CREATE_STAMP =
      field(name("create_stamp"), Timestamp.class);
  private static final Field<Timestamp> MODIFY_STAMP =
      field(name("modify_stamp"), Timestamp.class);
  private static final Field<String> SEX = field(name("sex"), String.class);
  private static final Field<Timestamp> BIRTHDAY = field(name("birthday"), Timestamp.class);
  private static final Field<Boolean> DELETED = field(name("deleted"), Boolean.class);
  private static final Field<Long> VERSION = field(name("version"), Long.class);

  private final DSLContext dsl;

  public JooqUserRepository(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    Settings settings = new Settings().withExecuteLogging(false).withRenderQuotedNames(NEVER);
    this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES, settings);
  }

  public JooqUser findById(Long id) {
    Record record =
        dsl.select(
                ID,
                USERNAME,
                PASSWORD,
                CREATE_STAMP,
                MODIFY_STAMP,
                SEX,
                BIRTHDAY,
                DELETED,
                VERSION)
            .from(SKIS_USER)
            .where(ID.eq(id))
            .fetchOne();
    return record == null ? null : readUser(record);
  }

  private static JooqUser readUser(Record record) {
    JooqUser user = new JooqUser();
    user.setId(record.get(ID));
    user.setUsername(record.get(USERNAME));
    user.setPassword(record.get(PASSWORD));
    user.setCreateStamp(toInstant(record.get(CREATE_STAMP)));
    user.setModifyStamp(toInstant(record.get(MODIFY_STAMP)));
    user.setSex(record.get(SEX));
    user.setBirthday(toInstant(record.get(BIRTHDAY)));
    user.setDeleted(record.get(DELETED));
    user.setVersion(record.get(VERSION));
    return user;
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
