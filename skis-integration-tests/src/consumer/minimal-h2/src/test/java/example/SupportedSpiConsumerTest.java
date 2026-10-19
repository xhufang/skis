package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import example.skis.PetMeta;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.SqlCompiler;
import io.skis.dialect.support.SqlTemplate;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.JdbcReadContext;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.TableExpression;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SupportedSpiConsumerTest {

  @Test
  void implementsSupportedDialectCodecAndConnectionContractsFromOutsideSkisPackages()
      throws Exception {
    Dialect dialect = new ExternalDialect();
    JdbcTypeCodec<String> codec = new ExternalStringCodec();
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:supported_spi_consumer;DB_CLOSE_DELAY=-1");
    ConnectionProvider connections = new ExternalConnectionProvider(dataSource);

    assertEquals("external", dialect.id());
    assertSame(StandardIdentifierRules.INSTANCE, dialect.identifierRules());
    assertSame(ExternalStringCodec.class, codec.getClass());

    PetAstTable pet = PetAstTable.PET;
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    SqlTemplate template =
        SqlCompiler.compile(
            new SelectStatement(List.of(pet.id()), pet, pet.id().eq(id)), dialect);
    assertEquals("SELECT \"pet\".\"id\" FROM \"pet\" WHERE \"pet\".\"id\" = ?", template.sql());
    assertEquals(List.of(id), template.parameterLayout().slots());
    SqlRenderException unsupported =
        assertThrows(
            SqlRenderException.class,
            () -> SqlCompiler.compile(new CountAst(pet, null, pet.id()), dialect));
    assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, unsupported.code());
    assertEquals("external", unsupported.diagnostics().dialect().dialectId());
    assertEquals(
        "COUNT_DISTINCT", unsupported.diagnostics().dialect().missingCapability());

    SqlRenderException constructed =
        SqlRenderException.of(
            SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
            "external",
            "FEATURE",
            "external dialect contract detail");
    assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, constructed.code());
    assertEquals("external", constructed.diagnostics().dialect().dialectId());
    assertEquals("FEATURE", constructed.diagnostics().dialect().missingCapability());

    Connection connection = connections.acquire(ExecutionContext.EMPTY);
    try {
      assertFalse(connection.isClosed());
    } finally {
      connections.release(connection, ExecutionContext.EMPTY);
    }
  }

  private static final class ExternalDialect implements Dialect {

    private static final DialectCapabilities CAPABILITIES =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES,
            DialectFeature.PARAMETERIZED_LIMIT,
            DialectFeature.PARAMETERIZED_OFFSET);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

    @Override
    public String id() {
      return "external";
    }

    @Override
    public IdentifierRules identifierRules() {
      return StandardIdentifierRules.INSTANCE;
    }

    @Override
    public DialectCapabilities capabilities() {
      return CAPABILITIES;
    }

    @Override
    public SqlRenderer renderer() {
      return renderer;
    }
  }

  private static final class ExternalStringCodec implements JdbcTypeCodec<String> {

    @Override
    public @Nullable String read(ResultSet resultSet, int index, JdbcReadContext context)
        throws SQLException {
      return resultSet.getString(index);
    }

    @Override
    public void bind(
        PreparedStatement statement,
        int index,
        @Nullable String value,
        JdbcWriteContext context)
        throws SQLException {
      statement.setString(index, value);
    }
  }

  private record ExternalConnectionProvider(DataSource dataSource) implements ConnectionProvider {

    @Override
    public Connection acquire(ExecutionContext context) throws SQLException {
      return dataSource.getConnection();
    }

    @Override
    public void release(Connection connection, ExecutionContext context) throws SQLException {
      connection.close();
    }
  }

  private static final class PetAstTable extends TableExpression<Pet> {

    private static final PetAstTable PET = new PetAstTable();

    private final ColumnExpression<Pet, Long> id = column(PetMeta.ID);

    private PetAstTable() {
      super(PetMeta.ENTITY);
    }

    private PetAstTable(Identifier alias) {
      super(PetMeta.ENTITY, alias);
    }

    private ColumnExpression<Pet, Long> id() {
      return id;
    }

    @Override
    public PetAstTable as(String alias) {
      return new PetAstTable(Identifier.of(alias));
    }

    @Override
    public PetAstTable as(Identifier alias) {
      return new PetAstTable(alias);
    }
  }
}
