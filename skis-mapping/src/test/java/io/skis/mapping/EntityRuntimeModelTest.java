package io.skis.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.List;
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

  private static EntityRuntimeModel<Pet> model() {
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(7L, "Mimi"),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
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
}
