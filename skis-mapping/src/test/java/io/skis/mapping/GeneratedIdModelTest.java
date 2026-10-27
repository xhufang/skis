package io.skis.mapping;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.SkisConfigurationException;
import io.skis.mapping.spi.*;
import io.skis.metadata.*;
import java.sql.*;
import java.util.List;
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
    var id = new PropertyMeta<Item, Long>(0, "id", Long.class, ColumnMeta.of("id", false));
    var entity = EntityMeta.simple(Item.class, TableMeta.of("item"), List.of(id),
        new PrimaryKeyMeta<>(List.of(id), IdGeneration.IDENTITY), false);
    ParameterBinder<Item> binder = (statement, index, item, context) -> index;
    var binders = new EntityMutationBinders<>(binder, binder, binder, null,
        (statement, index, arguments, context) -> index, new EntityIdAccess<Item>() {
          @Override public @Nullable Object read(Item entity) { return entity.id(); }
          @Override public Item apply(Item entity, Object id) { return new Item((Long) id); }
        });
    JdbcTypeCodec<Long> custom = new JdbcTypeCodec<>() {
      @Override public @Nullable Long read(ResultSet rows, int index, JdbcReadContext context)
          throws SQLException { return JdbcCodecs.LONG.read(rows, index, context); }
      @Override public void bind(PreparedStatement statement, int index, @Nullable Long value,
          JdbcWriteContext context) throws SQLException { JdbcCodecs.LONG.bind(statement, index, value, context); }
    };
    assertThrows(SkisConfigurationException.class, () -> new EntityRuntimeModel<>(entity,
        layout -> (rows, context) -> new Item(1L), List.of(new PropertyRuntime<>(id, custom)), binders));
    assertDoesNotThrow(() -> new EntityRuntimeModel<>(entity,
        layout -> (rows, context) -> new Item(1L), List.of(new PropertyRuntime<>(id, JdbcCodecs.LONG)), binders));
  }

  private record Item(@Nullable Long id) {}
}
