package io.skis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.SkisConfigurationException;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.Projection;
import io.skis.query.ProjectionProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectionModelLoaderTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class, TableMeta.of("pet"), List.of(ID), new PrimaryKeyMeta<>(List.of(ID)), false);
  private static final Projection<Pet, PetSummary> PROJECTION =
      Projection.generated(
          PetSummary.class,
          PET,
          Projection.mapping(TestProvider.class),
          List.of(ID),
          readers -> {
            Projection.ValueReader<Long> id = readers.reader(0, ID);
            return (resultSet, context) -> new PetSummary(id.read(resultSet, context));
          });
  private static final Projection<Pet, PetSummary> DUPLICATE_PROJECTION =
      Projection.generated(
          PetSummary.class,
          PET,
          Projection.mapping(DuplicateResultProvider.class),
          List.of(ID),
          readers -> {
            Projection.ValueReader<Long> id = readers.reader(0, ID);
            return (resultSet, context) -> new PetSummary(id.read(resultSet, context));
          });

  @TempDir Path temporaryDirectory;

  @Test
  void loadsGeneratedProjectionProvidersFromTheDeterministicIndex() throws Exception {
    writeIndex("# skis-generated-abi=4\n" + TestProvider.class.getName() + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      var registry = ProjectionModelLoader.load(classLoader);

      assertEquals(1, registry.size());
      assertSame(PROJECTION, registry.find(PetSummary.class).orElseThrow());
    }
  }

  @Test
  void rejectsAProjectionIndexWithoutAnAbiHeader() throws Exception {
    writeIndex(TestProvider.class.getName() + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      assertThrows(SkisConfigurationException.class, () -> ProjectionModelLoader.load(classLoader));
    }
  }

  @Test
  void reportsANullProjectionFromAGeneratedProvider() throws Exception {
    writeIndex("# skis-generated-abi=4\n" + NullProvider.class.getName() + "\n");
    try (URLClassLoader classLoader =
        new URLClassLoader(
            new java.net.URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> ProjectionModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains(NullProvider.class.getName()));
      assertTrue(failure.getMessage().contains("returned a null projection"));
    }
  }

  @Test
  void reportsAnIncompatibleProjectionIndexAbi() throws Exception {
    writeIndex("# skis-generated-abi=1\n" + TestProvider.class.getName() + "\n");
    try (URLClassLoader classLoader = classLoader(temporaryDirectory)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> ProjectionModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains("incompatible generated-model ABI '1'"));
      assertTrue(failure.getMessage().contains("projection index"));
    }
  }

  @Test
  void rejectsTheSameProjectionProviderDeclaredByTwoModuleIndexes() throws Exception {
    Path firstModule = temporaryDirectory.resolve("first-module");
    Path secondModule = temporaryDirectory.resolve("second-module");
    String content = "# skis-generated-abi=4\n" + TestProvider.class.getName() + "\n";
    writeIndex(firstModule, content);
    writeIndex(secondModule, content);

    try (URLClassLoader classLoader = classLoader(secondModule, firstModule)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> ProjectionModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains("duplicate generated projection provider"));
      assertTrue(failure.getMessage().contains("first-module"), failure.getMessage());
      assertTrue(failure.getMessage().contains("second-module"), failure.getMessage());
    }
  }

  @Test
  void reportsBothProvidersThatSupplyTheSameProjectionResultType() throws Exception {
    writeIndex(
        "# skis-generated-abi=4\n"
            + TestProvider.class.getName()
            + "\n"
            + DuplicateResultProvider.class.getName()
            + "\n");

    try (URLClassLoader classLoader = classLoader(temporaryDirectory)) {
      var failure =
          assertThrows(
              SkisConfigurationException.class, () -> ProjectionModelLoader.load(classLoader));

      assertTrue(failure.getMessage().contains(TestProvider.class.getName()));
      assertTrue(failure.getMessage().contains(DuplicateResultProvider.class.getName()));
      assertTrue(failure.getMessage().contains(PetSummary.class.getName()));
      assertTrue(failure.getMessage().contains("both supply projection result type"));
    }
  }

  private void writeIndex(String content) throws Exception {
    writeIndex(temporaryDirectory, content);
  }

  private static void writeIndex(Path root, String content) throws Exception {
    Path index = root.resolve(ProjectionModelLoader.INDEX_PATH);
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

  public static final class TestProvider implements ProjectionProvider {

    public TestProvider() {}

    @Override
    public Projection<Pet, PetSummary> projection() {
      return PROJECTION;
    }
  }

  public static final class NullProvider implements ProjectionProvider {

    public NullProvider() {}

    @Override
    public Projection<?, ?> projection() {
      return null;
    }
  }

  public static final class DuplicateResultProvider implements ProjectionProvider {

    public DuplicateResultProvider() {}

    @Override
    public Projection<Pet, PetSummary> projection() {
      return DUPLICATE_PROJECTION;
    }
  }

  private record Pet(Long id) {}

  private record PetSummary(Long id) {}
}
