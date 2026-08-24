package io.skis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeModelProvider;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityRuntimeModelLoaderTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          TableMeta.of("pet"),
          List.of(ID),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final EntityRuntimeModel<Pet> MODEL =
      new EntityRuntimeModel<>(
          PET,
          layout -> (resultSet, context) -> new Pet(1L),
          List.of(new PropertyRuntime<>(ID, JdbcCodecs.LONG)));

  @TempDir Path temporaryDirectory;

  @Test
  void loadsGeneratedProvidersFromIndexWithoutClasspathScanning() throws Exception {
    writeIndex(
        "# skis-generated-abi=2\n"
            + TestProvider.class.getName()
            + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      var registry = EntityRuntimeModelLoader.load(classLoader);

      assertEquals(1, registry.size());
      assertSame(MODEL, registry.require(PET));
    }
  }

  @Test
  void rejectsIndexWithoutAbiHeader() throws Exception {
    writeIndex(TestProvider.class.getName() + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      assertThrows(
          io.skis.core.SkisConfigurationException.class,
          () -> EntityRuntimeModelLoader.load(classLoader));
    }
  }

  @Test
  void reportsIncompatibleAbiAsAnAssemblyConfigurationFailure() throws Exception {
    writeIndex("# skis-generated-abi=1\n" + TestProvider.class.getName() + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      var failure =
          assertThrows(
              io.skis.core.SkisConfigurationException.class,
              () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains("incompatible generated-model ABI '1'"));
    }
  }

  @Test
  void reportsNullProviderModelAsAnAssemblyConfigurationFailure() throws Exception {
    writeIndex("# skis-generated-abi=2\n" + NullProvider.class.getName() + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      var failure =
          assertThrows(
              io.skis.core.SkisConfigurationException.class,
              () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains(NullProvider.class.getName()));
      assertTrue(failure.getMessage().contains("returned a null runtime model"));
    }
  }

  private void writeIndex(String content) throws Exception {
    Path index = temporaryDirectory.resolve(EntityRuntimeModelLoader.INDEX_PATH);
    Files.createDirectories(index.getParent());
    Files.writeString(index, content, StandardCharsets.UTF_8);
  }

  public static final class TestProvider implements EntityRuntimeModelProvider {

    public TestProvider() {}

    @Override
    public EntityRuntimeModel<Pet> model() {
      return MODEL;
    }
  }

  public static final class NullProvider implements EntityRuntimeModelProvider {

    public NullProvider() {}

    @Override
    public EntityRuntimeModel<?> model() {
      return null;
    }
  }

  public record Pet(Long id) {}
}
