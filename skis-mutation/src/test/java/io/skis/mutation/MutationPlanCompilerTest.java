package io.skis.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.MutationException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.spi.EntityMutationBinders;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.mutation.internal.MutationRuntime;
import io.skis.sql.ast.ParameterSlot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutationPlanCompilerTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          TableMeta.of("pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void rejectsLoweringWhoseParameterIdentityDoesNotMatchTheMutationAst() {
    io.skis.dialect.SqlRenderException failure =
        assertThrows(
            io.skis.dialect.SqlRenderException.class,
            () ->
                MutationRuntime.compile(
                    EntityRuntimeRegistry.of(List.of(runtimeModel())), ReorderingDialect.INSTANCE));

    assertEquals(io.skis.core.SkisErrorCode.DIALECT_PARAMETER_SHAPE, failure.code());
  }

  private static EntityRuntimeModel<Pet> runtimeModel() {
    EntityMutationBinders<Pet> binders =
        new EntityMutationBinders<>(
            (statement, firstIndex, value, context) -> firstIndex + 2,
            (statement, firstIndex, value, context) -> firstIndex + 2,
            (statement, firstIndex, value, context) -> firstIndex + 2,
            null);
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(1L, "Mimi"),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)),
        binders);
  }

  private record Pet(Long id, String name) {}

  private enum ReorderingDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

    @Override
    public io.skis.dialect.spi.DialectLowering lowering() {
      return input -> {
        if (input.statement() instanceof io.skis.sql.ast.InsertStatement insert) {
          List<io.skis.sql.ast.SqlExpression<?>> values = new ArrayList<>(insert.values().size());
          for (io.skis.sql.ast.SqlExpression<?> value : insert.values()) {
            if (value instanceof ParameterSlot<?> slot) {
              int swapped = slot.ordinal() == 0 ? 1 : 0;
              values.add(
                  new ParameterSlot<>(
                      swapped, slot.javaType(), slot.sqlType(), slot.nullability()));
            } else {
              values.add(value);
            }
          }
          return new io.skis.sql.ast.InsertStatement(insert.target(), insert.columns(), values);
        }
        return input.statement();
      };
    }

    @Override
    public String id() {
      return "reordering";
    }

    @Override
    public IdentifierRules identifierRules() {
      return StandardIdentifierRules.INSTANCE;
    }

    @Override
    public DialectCapabilities capabilities() {
      return capabilities;
    }

    @Override
    public SqlRenderer renderer() {
      return renderer;
    }
  }
}
