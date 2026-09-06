package io.skis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.SkisConfigurationException;
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
          Pet.class, TableMeta.of("pet"), List.of(ID), new PrimaryKeyMeta<>(List.of(ID)), false);
  private static final EntityRuntimeModel<Pet> MODEL =
      new EntityRuntimeModel<>(
          PET,
          layout -> (resultSet, context) -> new Pet(1L),
          List.of(new PropertyRuntime<>(ID, JdbcCodecs.LONG)));
  private static final PropertyMeta<Pet, Long> DUPLICATE_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("duplicate_id", false));
  private static final EntityMeta<Pet> DUPLICATE_PET =
      EntityMeta.simple(
          Pet.class,
          TableMeta.of("duplicate_pet"),
          List.of(DUPLICATE_ID),
          new PrimaryKeyMeta<>(List.of(DUPLICATE_ID)),
          false);
  private static final EntityRuntimeModel<Pet> DUPLICATE_MODEL =
      new EntityRuntimeModel<>(
          DUPLICATE_PET,
          layout -> (resultSet, context) -> new Pet(2L),
          List.of(new PropertyRuntime<>(DUPLICATE_ID, JdbcCodecs.LONG)));

  @TempDir Path temporaryDirectory;

  @Test
  void loadsGeneratedProvidersFromIndexWithoutClasspathScanning() throws Exception {
    writeIndex("# skis-generated-abi=5\n" + TestProvider.class.getName() + "\n");
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
          SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));
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
              SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains("incompatible generated-model ABI '1'"));
    }
  }

  @Test
  void reportsNullProviderModelAsAnAssemblyConfigurationFailure() throws Exception {
    writeIndex("# skis-generated-abi=5\n" + NullProvider.class.getName() + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains(NullProvider.class.getName()));
      assertTrue(failure.getMessage().contains("returned a null runtime model"));
    }
  }

  @Test
  void rejectsDuplicateAbiHeadersWithBothLineNumbers() throws Exception {
    writeIndex(
        "# skis-generated-abi=5\n# skis-generated-abi=5\n" + TestProvider.class.getName() + "\n");
    try (URLClassLoader classLoader = classLoader(temporaryDirectory)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains("more than once"), failure.getMessage());
      assertTrue(failure.getMessage().contains("lines 1 and 2"), failure.getMessage());
    }
  }

  @Test
  void rejectsDuplicateProviderLinesWithinOneIndex() throws Exception {
    writeIndex(
        "# skis-generated-abi=5\n"
            + TestProvider.class.getName()
            + "\n"
            + TestProvider.class.getName()
            + "\n");
    try (URLClassLoader classLoader = classLoader(temporaryDirectory)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains("duplicate generated entity provider"));
      assertTrue(failure.getMessage().contains(":2"), failure.getMessage());
      assertTrue(failure.getMessage().contains(":3"), failure.getMessage());
    }
  }

  @Test
  void rejectsTheSameProviderDeclaredByTwoModuleIndexes() throws Exception {
    Path firstModule = temporaryDirectory.resolve("first-module");
    Path secondModule = temporaryDirectory.resolve("second-module");
    String content = "# skis-generated-abi=5\n" + TestProvider.class.getName() + "\n";
    writeIndex(firstModule, content);
    writeIndex(secondModule, content);

    try (URLClassLoader classLoader = classLoader(secondModule, firstModule)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains("duplicate generated entity provider"));
      assertTrue(failure.getMessage().contains("first-module"), failure.getMessage());
      assertTrue(failure.getMessage().contains("second-module"), failure.getMessage());
    }
  }

  @Test
  void reportsBothProvidersThatSupplyTheSameEntityJavaType() throws Exception {
    writeIndex(
        "# skis-generated-abi=5\n"
            + TestProvider.class.getName()
            + "\n"
            + DuplicateTypeProvider.class.getName()
            + "\n");

    try (URLClassLoader classLoader = classLoader(temporaryDirectory)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains(TestProvider.class.getName()));
      assertTrue(failure.getMessage().contains(DuplicateTypeProvider.class.getName()));
      assertTrue(failure.getMessage().contains(Pet.class.getName()));
      assertTrue(failure.getMessage().contains("both supply entity Java type"));
    }
  }

  @Test
  void reportsBothProvidersThatSupplyTheSameCanonicalEntityMetadata() throws Exception {
    writeIndex(
        "# skis-generated-abi=5\n"
            + TestProvider.class.getName()
            + "\n"
            + DuplicateCanonicalProvider.class.getName()
            + "\n");

    try (URLClassLoader classLoader = classLoader(temporaryDirectory)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> EntityRuntimeModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains(TestProvider.class.getName()));
      assertTrue(failure.getMessage().contains(DuplicateCanonicalProvider.class.getName()));
      assertTrue(failure.getMessage().contains(PET.entityName()));
      assertTrue(failure.getMessage().contains("both supply canonical entity metadata"));
    }
  }

  private void writeIndex(String content) throws Exception {
    writeIndex(temporaryDirectory, content);
  }

  private static void writeIndex(Path root, String content) throws Exception {
    Path index = root.resolve(EntityRuntimeModelLoader.INDEX_PATH);
    Files.createDirectories(index.getParent());
    Files.writeString(index, content, StandardCharsets.UTF_8);
  }

  private URLClassLoader classLoader(Path... roots) throws Exception {
    java.net.URL[] urls = new java.net.URL[roots.length];
    for (int index = 0; index < roots.length; index++) {
      urls[index] = roots[index].toUri().toURL();
    }
    return new URLClassLoader(urls, getClass().getClassLoader());
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

  public static final class DuplicateTypeProvider implements EntityRuntimeModelProvider {

    public DuplicateTypeProvider() {}

    @Override
    public EntityRuntimeModel<Pet> model() {
      return DUPLICATE_MODEL;
    }
  }

  public static final class DuplicateCanonicalProvider implements EntityRuntimeModelProvider {

    public DuplicateCanonicalProvider() {}

    @Override
    public EntityRuntimeModel<Pet> model() {
      return MODEL;
    }
  }

  public record Pet(Long id) {}
}
