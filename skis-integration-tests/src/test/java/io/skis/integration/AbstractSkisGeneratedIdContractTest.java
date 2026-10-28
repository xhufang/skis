package io.skis.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.*;
import io.skis.core.spi.*;
import io.skis.dialect.spi.Dialect;
import io.skis.integration.ids.*;
import io.skis.integration.ids.skis.*;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mutation.SnowflakeIdGenerator;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Same generated models and write contracts against H2 and the required real PostgreSQL paths. */
abstract class AbstractSkisGeneratedIdContractTest {
  protected abstract DataSource createDataSource();
  protected abstract Dialect dialect();

  private static final List<EntityRuntimeModel<?>> MODELS = List.of(
      AssignedIdRecordRuntimeModel.MODEL, IdentityIdRecordRuntimeModel.MODEL,
      IdentityOnlyIdRecordRuntimeModel.MODEL, IntegerIdentityIdRecordRuntimeModel.MODEL,
      UuidIdRecordRuntimeModel.MODEL, SnowflakeIdRecordRuntimeModel.MODEL,
      IdentityIdBeanRuntimeModel.MODEL, UuidStringIdBeanRuntimeModel.MODEL,
      VersionIdRecordRuntimeModel.MODEL, RejectingIdRecordRuntimeModel.MODEL);

  @Test
  void defaultValuesUsesTheControlledCompilerAndRejectsMissingCapabilities() {
    var entity = IdentityOnlyIdRecordRuntimeModel.MODEL.entity();
    var table = new io.skis.sql.ast.TableExpression<IdentityOnlyIdRecord>(entity) {
      @Override public io.skis.sql.ast.TableExpression<IdentityOnlyIdRecord> as(
          io.skis.sql.ast.Identifier alias) { throw new UnsupportedOperationException(); }
    };
    var ast = new io.skis.sql.ast.InsertStatement(table, List.of(), List.of());
    var template = io.skis.dialect.support.SqlCompiler.compile(ast, dialect());
    assertEquals("INSERT INTO \"identityonlyidrecord\" DEFAULT VALUES", template.sql());
    assertTrue(template.parameterLayout().slots().isEmpty());
    var unsupported = new io.skis.dialect.spi.Dialect() {
      private final io.skis.dialect.spi.SqlRenderer renderer =
          new io.skis.dialect.support.StandardSqlRenderer(this);
      @Override public String id() { return "no-generated-keys"; }
      @Override public io.skis.dialect.spi.IdentifierRules identifierRules() {
        return dialect().identifierRules();
      }
      @Override public io.skis.dialect.spi.DialectCapabilities capabilities() {
        return io.skis.dialect.spi.DialectCapabilities.of(dialect().capabilities().features().stream()
            .filter(feature -> feature != io.skis.dialect.spi.DialectFeature.JDBC_GENERATED_KEYS
                && feature != io.skis.dialect.spi.DialectFeature.INSERT_DEFAULT_VALUES)
            .toArray(io.skis.dialect.spi.DialectFeature[]::new));
      }
      @Override public io.skis.dialect.spi.SqlRenderer renderer() { return renderer; }
    };
    assertThrows(io.skis.dialect.SqlRenderException.class,
        () -> io.skis.dialect.support.SqlCompiler.compile(ast, unsupported));
    var source = createDataSource();
    assertThrows(io.skis.dialect.SqlRenderException.class, () -> Skis.builder(source)
        .dialect(unsupported).runtimeModels(List.of(IdentityOnlyIdRecordRuntimeModel.MODEL)).build());
    assertEquals(SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID,
        assertThrows(SkisConfigurationException.class,
            () -> Skis.builder(source).dialect(dialect()).runtimeModels(MODELS).build()).code());
  }

  @Test
  void executorsAndSessionsCanUseOneExplicitlySharedSnowflakeGenerator() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      var generator = SnowflakeIdGenerator.ofNode(29);
      var compilations = new AtomicInteger();
      var first = executor(source, connection, new AtomicInteger(), new AtomicInteger(), generator, compilations);
      var second = executor(source, connection, new AtomicInteger(), new AtomicInteger(), generator, compilations);
      int precompiled = compilations.get();
      assertTrue(precompiled > 0);
      Long firstId = first.insertInto(SnowflakeIdRecordTable.snowflakeIdRecordTable).value(new SnowflakeIdRecord(null, "first")).returning().write().get().id();
      Long sessionId;
      try (var session = second.beginTransaction()) {
        sessionId = session.insertInto(SnowflakeIdRecordTable.snowflakeIdRecordTable).value(new SnowflakeIdRecord(null, "session")).returning().write().get().id();
        session.commit();
      }
      assertTrue(sessionId > firstId);
      assertEquals(29, (sessionId >>> 12) & 1023);
      assertEquals(precompiled + 2, compilations.get(),
          "each executor compiles its returning shape once; Session reuses its catalog");
    }
  }

  @Test
  void externalRollbackDoesNotClearTheConfirmedKeyOrChangeConnectionOwnership() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      connection.setAutoCommit(false);
      var releases = new AtomicInteger();
      var executor = Skis.builder(source).dialect(dialect())
          .runtimeModels(List.of(IdentityIdBeanRuntimeModel.MODEL))
          .connectionProvider(new ConnectionProvider() {
            @Override public boolean supportsLocalTransactions() { return false; }
            @Override public Connection acquire(ExecutionContext context) { return connection; }
            @Override public void release(Connection released, ExecutionContext context) {
              assertSame(connection, released);
              releases.incrementAndGet();
            }
          }).build();
      var bean = new IdentityIdBean();
      executor.insertInto(IdentityIdBeanTable.identityIdBeanTable).value(bean).returning().write();
      Long key = bean.getId();
      assertNotNull(key);
      assertFalse(connection.getAutoCommit());
      assertEquals(1, releases.get());
      connection.rollback();
      assertEquals(key, bean.getId());
      assertEquals(1, bean.writes);
      assertFalse(connection.isClosed());
      assertTrue(executor.findById(IdentityIdBeanTable.identityIdBeanTable, key).isEmpty());
      connection.setAutoCommit(true);
    }
  }

  @Test
  void returnsExactIntegerKeysAndDecodesPersistentComponents() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      var acquisitions = new AtomicInteger();
      var statements = new AtomicInteger();
      var executor = executor(source, connection, acquisitions, statements);
      var input = new IdentityIdRecord(null, "record", "keep transient");
      var result = executor.insertInto(IdentityIdRecordTable.identityIdRecordTable).value(input).returning().write();
      assertEquals(1, result.affectedRows());
      assertNull(input.id());
      assertNotNull(result.get().id());
      assertNull(result.get().note(), "full RETURNING uses the entity decoder's transient default");
      assertNotSame(input, result.get());
      assertEquals(1, statements.get(), "RETURNING must not issue a hidden SELECT");
      var idOnly = executor.insertInto(IdentityOnlyIdRecordTable.identityOnlyIdRecordTable).value(new IdentityOnlyIdRecord(null)).returning().write();
      assertNotNull(idOnly.get().id());
      var integer = executor.insertInto(IntegerIdentityIdRecordTable.integerIdentityIdRecordTable).value(new IntegerIdentityIdRecord(null, "integer")).returning().write();
      assertInstanceOf(Integer.class, integer.get().id());
      assertEquals("record", executor.findById(IdentityIdRecordTable.identityIdRecordTable,
          result.get().id()).orElseThrow().name());
    }
  }

  @Test
  void bindsFourStrategiesAndReturnsActualDatabaseValues() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      var executor = executor(source, connection, new AtomicInteger(), new AtomicInteger());
      var assigned = new AssignedIdRecord(0L, "zero is assigned");
      assertEquals(assigned, executor.insertInto(AssignedIdRecordTable.assignedIdRecordTable).value(assigned).returning().write().get());
      assertEquals(1, executor.insertInto(AssignedIdRecordTable.assignedIdRecordTable).value(new AssignedIdRecord(1L, "count fast path")).write());
      var uuid = executor.insertInto(UuidIdRecordTable.uuidIdRecordTable).value(new UuidIdRecord(null, "uuid")).returning().write();
      assertEquals(4, uuid.get().id().version());
      assertEquals(2, uuid.get().id().variant());
      var supplied = new UuidIdRecord(UUID.fromString("00000000-0000-1000-8000-000000000001"), "supplied");
      assertEquals(supplied, executor.insertInto(UuidIdRecordTable.uuidIdRecordTable).value(supplied).returning().write().get());
      var snowflake = executor.insertInto(SnowflakeIdRecordTable.snowflakeIdRecordTable).value(new SnowflakeIdRecord(null, "snowflake")).returning().write();
      assertTrue(snowflake.get().id() > 0);
      assertEquals(17, (snowflake.get().id() >>> 12) & 1023);
      var versioned = executor.insertInto(VersionIdRecordTable.versionIdRecordTable).value(new VersionIdRecord(null, null, "version")).returning().write();
      assertEquals(0L, versioned.get().version(), "RETURNING reads the initialized database version");
      assertEquals(Long.valueOf(0L), executor.findById(VersionIdRecordTable.versionIdRecordTable,
          versioned.get().id()).orElseThrow().version());
      assertThrows(JdbcExecutionException.class,
          () -> executor.insertInto(UuidIdRecordTable.uuidIdRecordTable).value(supplied).returning().write());
    }
  }

  @Test
  void readsGeneratedBeanIdsOnceAndAppliesOnlyNewKeysAfterExecution() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      var executor = executor(source, connection, new AtomicInteger(), new AtomicInteger());
      var bean = new IdentityIdBean();
      assertEquals(1, executor.insertInto(IdentityIdBeanTable.identityIdBeanTable).value(bean).write());
      assertEquals(1, bean.reads);
      assertEquals(1, bean.writes);
      assertNotNull(bean.getId());
      var text = new UuidStringIdBean();
      assertNotSame(text, executor.insertInto(UuidStringIdBeanTable.uuidStringIdBeanTable).value(text).returning().write().get());
      assertEquals(1, text.reads);
      assertEquals(1, text.writes);
      assertEquals(UUID.fromString(text.getId()).toString(), text.getId());
      var existing = new UuidStringIdBean();
      existing.setId("00000000-0000-1000-8000-000000000002");
      existing.writes = 0;
      executor.insertInto(UuidStringIdBeanTable.uuidStringIdBeanTable).value(existing).write();
      assertEquals(0, existing.writes, "supplied keys must not be written again");
      assertEquals(1, existing.reads);
    }
  }

  @Test
  void validatesInputsAndGeneratedRecordCountCallsBeforeConnectionAcquisition() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      var acquisitions = new AtomicInteger();
      var executor = executor(source, connection, acquisitions, new AtomicInteger());
      assertEquals(SkisErrorCode.MUTATION_REQUIRED_VALUE_NULL,
          assertThrows(MutationException.class, () -> executor.insertInto(AssignedIdRecordTable.assignedIdRecordTable).value(new AssignedIdRecord(null, "bad")).write()).code());
      assertEquals(SkisErrorCode.MUTATION_ID_VALUE_INVALID,
          assertThrows(MutationException.class, () -> executor.insertInto(IdentityIdRecordTable.identityIdRecordTable).value(new IdentityIdRecord(7L, "bad", null)).returning().write()).code());
      assertThrows(MutationException.class, () -> executor.insertInto(SnowflakeIdRecordTable.snowflakeIdRecordTable).value(new SnowflakeIdRecord(0L, "bad")).returning().write());
      assertThrows(MutationException.class, () -> executor.insertInto(UuidIdRecordTable.uuidIdRecordTable).value(new UuidIdRecord(UUID.randomUUID(), "record count call")).write());
      for (String text : List.of("", " ", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "1-1-1-1-1")) {
        var bean = new UuidStringIdBean();
        bean.setId(text);
        assertThrows(MutationException.class,
            () -> executor.insertInto(UuidStringIdBeanTable.uuidStringIdBeanTable).value(bean).write());
      }
      assertEquals(0, acquisitions.get());
    }
  }

  @Test
  void keepsConfirmedInMemoryKeysAfterTransactionRollbackAndSharesTheGenerator() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      var statements = new AtomicInteger();
      var executor = executor(source, connection, new AtomicInteger(), statements);
      var bean = new IdentityIdBean();
      Long snowId;
      try (var session = executor.beginTransaction()) {
        session.insertInto(IdentityIdBeanTable.identityIdBeanTable).value(bean).returning().write();
        snowId = session.insertInto(SnowflakeIdRecordTable.snowflakeIdRecordTable).value(new SnowflakeIdRecord(null, "rollback")).returning().write().get().id();
        session.rollback();
      }
      assertNotNull(bean.getId());
      assertEquals(1, bean.writes);
      assertTrue(executor.findById(IdentityIdBeanTable.identityIdBeanTable, bean.getId()).isEmpty());
      Long nextId = executor.insertInto(SnowflakeIdRecordTable.snowflakeIdRecordTable).value(new SnowflakeIdRecord(null, "after rollback")).returning().write().get().id();
      assertTrue(nextId > snowId);
      assertEquals(17, (nextId >>> 12) & 1023);
    }
  }

  @Test
  void preservesAccessorCausesAndDoesNotRetryAfterSetterOrConstructorFailure() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createTables(connection);
      var acquisitions = new AtomicInteger();
      var statements = new AtomicInteger();
      var executor = executor(source, connection, acquisitions, statements);
      var badReader = new IdentityIdBean();
      badReader.failRead = true;
      assertEquals(SkisErrorCode.MUTATION_ID_ACCESS_FAILED,
          assertThrows(MutationException.class,
              () -> executor.insertInto(IdentityIdBeanTable.identityIdBeanTable).value(badReader).write()).code());
      assertEquals(0, acquisitions.get());
      var badSetter = new IdentityIdBean();
      badSetter.failApply = true;
      var failed = assertThrows(MutationException.class,
          () -> executor.insertInto(IdentityIdBeanTable.identityIdBeanTable).value(badSetter).write());
      assertEquals(SkisErrorCode.MUTATION_ID_APPLY_FAILED, failed.code());
      assertInstanceOf(IllegalStateException.class, failed.getCause());
      assertEquals(1, badSetter.writes);
      assertEquals(1, statements.get());
      assertNull(badSetter.getId());
      assertThrows(IllegalStateException.class, () -> executor.insertInto(
          RejectingIdRecordTable.rejectingIdRecordTable).value(new RejectingIdRecord(null))
          .returning().write());
      assertEquals(2, statements.get());
      try (var query = connection.createStatement();
          var rows = query.executeQuery("SELECT COUNT(*) FROM \"identityidbean\"")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1), "failed application must not pretend the SQL was undone");
      }
    }
  }

  private SkisExecutor executor(DataSource source, Connection connection, AtomicInteger acquisitions,
      AtomicInteger statements) {
    return executor(source, connection, acquisitions, statements, SnowflakeIdGenerator.ofNode(17));
  }

  private SkisExecutor executor(DataSource source, Connection connection, AtomicInteger acquisitions,
      AtomicInteger statements, SnowflakeIdGenerator generator) {
    return executor(source, connection, acquisitions, statements, generator, new AtomicInteger());
  }

  private SkisExecutor executor(DataSource source, Connection connection, AtomicInteger acquisitions,
      AtomicInteger statements, SnowflakeIdGenerator generator, AtomicInteger compilations) {
    return Skis.builder(source).dialect(dialect()).runtimeModels(MODELS)
        .snowflakeIdGenerator(generator)
        .connectionProvider(new ConnectionProvider() {
          @Override public Connection acquire(ExecutionContext context) {
            acquisitions.incrementAndGet(); return connection;
          }
          @Override public void release(Connection released, ExecutionContext context) {
            assertSame(connection, released);
          }
        }).listener(new ExecutionListener() {
          @Override public void compilationCompleted(CompilationCompletedEvent event) {
            compilations.incrementAndGet();
          }
          @Override public void statementStarted(StatementStartedEvent event) {
            statements.incrementAndGet();
          }
        }).build();
  }

  private static void createTables(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      for (String ddl : List.of(
          "assignedidrecord (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
          "identityidrecord (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR(100) NOT NULL)",
          "identityonlyidrecord (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY)",
          "integeridentityidrecord (id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR(100) NOT NULL)",
          "uuididrecord (id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL)",
          "snowflakeidrecord (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
          "identityidbean (\"Generated Key\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR(100) NOT NULL)",
          "uuidstringidbean (\"Generated Key\" VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL)",
          "versionidrecord (id UUID PRIMARY KEY, version BIGINT NOT NULL, name VARCHAR(100) NOT NULL)",
          "rejectingidrecord (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY)")) {
        // Explicit lowercase quoted names match the generated metadata on both databases.
        int split = ddl.indexOf(' ');
        String name = ddl.substring(0, split);
        String definition = ddl.substring(split).replace("(id ", "(\"id\" ")
            .replace(", name ", ", \"name\" ").replace(", version ", ", \"version\" ");
        statement.execute("CREATE TEMPORARY TABLE \"" + name + "\"" + definition);
      }
    }
  }
}
