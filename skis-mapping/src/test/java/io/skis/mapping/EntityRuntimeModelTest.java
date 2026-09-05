package io.skis.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EntityRuntimeModelTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          TableMeta.of("pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void keepsCanonicalMetadataDecoderFactoryAndCodecsTogether() {
    EntityRuntimeModel<Pet> model = model();

    assertSame(PET, model.entity());
    assertSame(ID, model.property(ID).property());
    assertSame(JdbcCodecs.LONG, model.property(ID).codec());
    assertEquals(2, model.properties().size());
    assertNotNull(model.rowDecoder(RowLayout.contiguous(2, 1)));
    assertSame(model.fullRowDecoder(), model.fullRowDecoder());
  }

  @Test
  void bindsThroughGeneratedPropertyCodecWithoutReflection() throws Exception {
    AtomicInteger index = new AtomicInteger();
    AtomicLong value = new AtomicLong();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, arguments) -> {
                  if (method.getName().equals("setLong")) {
                    index.set((Integer) arguments[0]);
                    value.set((Long) arguments[1]);
                  }
                  return defaultValue(method.getReturnType());
                });

    model().property(ID).bind(statement, 3, 41L, JdbcWriteContext.EMPTY);

    assertEquals(3, index.get());
    assertEquals(41L, value.get());
    assertThrows(
        IllegalArgumentException.class,
        () -> model().property(ID).bind(statement, 1, "wrong", JdbcWriteContext.EMPTY));
  }

  @Test
  void rejectsRuntimePropertiesThatAreNotCanonicalAndOrdered() {
    PropertyMeta<Pet, Long> distinctId =
        new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntityRuntimeModel<>(
                PET,
                layout -> (resultSet, context) -> new Pet(1L, "pet"),
                List.of(
                    new PropertyRuntime<>(distinctId, JdbcCodecs.LONG),
                    new PropertyRuntime<>(NAME, JdbcCodecs.STRING))));
  }

  @Test
  void nullableEntityDecoderChecksPrimaryKeyBeforeConstructingTheEntity() throws Exception {
    AtomicInteger decodedRows = new AtomicInteger();
    EntityRuntimeModel<Pet> model =
        new EntityRuntimeModel<>(
            PET,
            layout ->
                (resultSet, context) -> {
                  decodedRows.incrementAndGet();
                  return new Pet(7L, "Mimi");
                },
            List.of(
                new PropertyRuntime<>(ID, JdbcCodecs.LONG),
                new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
    RowDecoder<Pet> decoder = model.nullableRowDecoder(RowLayout.contiguous(2, 1));

    assertNull(decoder.decode(resultSet(Map.of(2, "orphan")), RowReadContext.EMPTY));
    assertEquals(0, decodedRows.get());
    assertEquals(
        new Pet(7L, "Mimi"),
        decoder.decode(resultSet(Map.of(1, 7L, 2, "Mimi")), RowReadContext.EMPTY));
    assertEquals(1, decodedRows.get());
  }

  @Test
  void nullableEntityDecoderRejectsPartiallyNullCompositePrimaryKey() {
    PropertyMeta<CompositePet, Long> tenantId =
        new PropertyMeta<>(0, "tenantId", Long.class, ColumnMeta.of("tenant_id", false));
    PropertyMeta<CompositePet, Long> petId =
        new PropertyMeta<>(1, "petId", Long.class, ColumnMeta.of("pet_id", false));
    EntityMeta<CompositePet> entity =
        EntityMeta.simple(
            CompositePet.class,
            TableMeta.of("composite_pet"),
            List.of(tenantId, petId),
            new PrimaryKeyMeta<>(List.of(tenantId, petId)),
            false);
    EntityRuntimeModel<CompositePet> model =
        new EntityRuntimeModel<>(
            entity,
            layout -> (resultSet, context) -> new CompositePet(1L, 2L),
            List.of(
                new PropertyRuntime<>(tenantId, JdbcCodecs.LONG),
                new PropertyRuntime<>(petId, JdbcCodecs.LONG)));
    RowDecoder<CompositePet> decoder =
        model.nullableRowDecoder(RowLayout.contiguous(2, 1));

    assertThrows(
        SQLException.class,
        () -> decoder.decode(resultSet(Map.of(1, 1L)), RowReadContext.EMPTY));
  }

  @Test
  void nullableEntityDecoderRequiresPrimaryKeyMetadata() {
    EntityMeta<Pet> readOnlyWithoutKey =
        EntityMeta.simple(
            Pet.class, TableMeta.of("pet_view"), List.of(ID, NAME), null, true);
    EntityRuntimeModel<Pet> model =
        new EntityRuntimeModel<>(
            readOnlyWithoutKey,
            layout -> (resultSet, context) -> new Pet(7L, "Mimi"),
            List.of(
                new PropertyRuntime<>(ID, JdbcCodecs.LONG),
                new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));

    assertThrows(
        IllegalArgumentException.class,
        () -> model.nullableRowDecoder(RowLayout.contiguous(2, 1)));
  }

  private static EntityRuntimeModel<Pet> model() {
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(7L, "Mimi"),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
  }

  private static ResultSet resultSet(Map<Integer, Object> values) {
    int[] lastIndex = new int[1];
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (ignored, method, arguments) -> {
              if (method.getName().equals("wasNull")) {
                return values.get(lastIndex[0]) == null;
              }
              if (method.getName().equals("getLong")) {
                int index = (Integer) arguments[0];
                lastIndex[0] = index;
                Object value = values.get(index);
                return value == null ? 0L : ((Number) value).longValue();
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    throw new AssertionError(type);
  }

  private record Pet(Long id, String name) {}

  private record CompositePet(Long tenantId, Long petId) {}
}
