package io.skis.mutation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.RenderedSql;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.mapping.EntityMutationBinders;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
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
  void rejectsDialectOutputWhoseParameterSlotsDoNotMatchTheMutationAst() {
    MutationException failure =
        assertThrows(
            MutationException.class,
            () ->
                MutationRuntime.compile(
                    EntityRuntimeRegistry.of(List.of(runtimeModel())),
                    ReorderingDialect.INSTANCE));

    assertTrue(failure.getMessage().contains("unexpected insert parameter shape"));
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
    private final SqlRenderer delegate =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

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
      return statement -> {
        RenderedSql rendered = delegate.render(statement);
        List<ParameterSlot<?>> reordered = new ArrayList<>(rendered.parameters());
        Collections.reverse(reordered);
        return new RenderedSql(rendered.sql(), reordered);
      };
    }
  }
}
