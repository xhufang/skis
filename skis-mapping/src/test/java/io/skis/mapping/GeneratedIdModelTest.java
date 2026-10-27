package io.skis.mapping;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import io.skis.mapping.spi.*;
import io.skis.metadata.*;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GeneratedIdModelTest {
  @Test
  void validatesManualGenerationMetadataAndRejectsMissingGeneratedBindings() {
    var id = new PropertyMeta<Item, Long>(0, "id", Long.class, ColumnMeta.of("id", false));
    var key = new PrimaryKeyMeta<>(List.of(id), IdGeneration.IDENTITY);
    var entity = EntityMeta.simple(Item.class, TableMeta.of("item"), List.of(id), key, false);
    assertThrows(SkisConfigurationException.class, () -> new EntityRuntimeModel<>(entity,
        layout -> (rows, context) -> new Item(1L), List.of(new PropertyRuntime<>(id, JdbcCodecs.LONG))));
    assertThrows(SkisConfigurationException.class, () -> EntityMeta.simple(
        Item.class, TableMeta.of("item"), List.of(id), key, true));
    assertThrows(SkisConfigurationException.class,
        () -> new PrimaryKeyMeta<>(List.of(id), IdGeneration.UUID));
    var integer = new PropertyMeta<Item, Integer>(1, "other", Integer.class, ColumnMeta.of("other", false));
    assertThrows(SkisConfigurationException.class,
        () -> new PrimaryKeyMeta<>(List.of(id, integer), IdGeneration.SNOWFLAKE));
  }

  @Test
  void refusesCustomGeneratedKeyCodecsAndPreservesCanonicalPropertyValidation() {
    assertGeneratedKeyCodec(Long.class, JdbcCodecs.LONG, IdGeneration.IDENTITY);
    assertGeneratedKeyCodec(Integer.class, JdbcCodecs.INTEGER, IdGeneration.IDENTITY);
    assertGeneratedKeyCodec(String.class, JdbcCodecs.STRING, IdGeneration.UUID);
    assertGeneratedKeyCodec(UUID.class, JdbcCodecs.UUID, IdGeneration.UUID);
    assertGeneratedKeyCodec(Long.class, JdbcCodecs.LONG, IdGeneration.SNOWFLAKE);
  }

  private static <I> void assertGeneratedKeyCodec(
      Class<I> type, JdbcTypeCodec<I> codec, IdGeneration generation) {
    var id = new PropertyMeta<Item, I>(0, "id", type, ColumnMeta.of("id", false));
    var entity = EntityMeta.simple(Item.class, TableMeta.of("item"), List.of(id),
        new PrimaryKeyMeta<>(List.of(id), generation), false);
    ParameterBinder<Item> binder = (statement, index, item, context) -> index;
    var binders = new EntityMutationBinders<>(binder, binder, binder, null,
        (statement, index, arguments, context) -> index, new EntityIdAccess<Item>() {
          @Override public @Nullable Object read(Item entity) { return entity.id(); }
          @Override public Item apply(Item entity, Object id) { return new Item(id); }
        });
    JdbcTypeCodec<I> custom = new JdbcTypeCodec<>() {
      @Override public @Nullable I read(ResultSet rows, int index, JdbcReadContext context)
          throws SQLException { return codec.read(rows, index, context); }
      @Override public void bind(PreparedStatement statement, int index, @Nullable I value,
          JdbcWriteContext context) throws SQLException { codec.bind(statement, index, value, context); }
    };
    var failure = assertThrows(SkisConfigurationException.class, () -> new EntityRuntimeModel<>(entity,
        layout -> (rows, context) -> new Item(null), List.of(new PropertyRuntime<>(id, custom)), binders));
    assertEquals(SkisErrorCode.METADATA_INVALID, failure.code());
    assertDoesNotThrow(() -> new EntityRuntimeModel<>(entity,
        layout -> (rows, context) -> new Item(null), List.of(new PropertyRuntime<>(id, codec)), binders));
  }

  private record Item(@Nullable Object id) {}
}
