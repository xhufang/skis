package io.skis.runtime;

import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisException;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.EntityRuntimeModelProvider;
import io.skis.metadata.EntityMeta;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Loads generated providers once from deterministic indexes without scanning the class path. */
final class EntityRuntimeModelLoader {

  static final String INDEX_PATH = "META-INF/skis/entities.idx";

  private EntityRuntimeModelLoader() {}

  static EntityRuntimeRegistry load(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "classLoader");
    List<GeneratedIndexReader.Entry> entries =
        GeneratedIndexReader.read(classLoader, INDEX_PATH, "entity");
    List<EntityRuntimeModel<?>> models = new ArrayList<>(entries.size());
    Map<EntityMeta<?>, LoadedEntity> byMetadata = new IdentityHashMap<>();
    Map<Class<?>, LoadedEntity> byJavaType = new IdentityHashMap<>();
    for (GeneratedIndexReader.Entry entry : entries) {
      EntityRuntimeModelProvider provider = loadProvider(classLoader, entry);
      EntityRuntimeModel<?> model = model(provider, entry);
      LoadedEntity loaded = new LoadedEntity(entry);
      LoadedEntity sameMetadata = byMetadata.putIfAbsent(model.entity(), loaded);
      if (sameMetadata != null) {
        throw duplicateModel(
            "canonical entity metadata for '" + model.entity().entityName() + "'",
            sameMetadata,
            loaded);
      }
      LoadedEntity sameJavaType = byJavaType.putIfAbsent(model.entity().javaType(), loaded);
      if (sameJavaType != null) {
        throw duplicateModel(
            "entity Java type '" + model.entity().javaType().getTypeName() + "'",
            sameJavaType,
            loaded);
      }
      models.add(model);
    }
    try {
      return EntityRuntimeRegistry.of(models);
    } catch (IllegalArgumentException failure) {
      throw SkisConfigurationException.of(
          SkisErrorCode.ASSEMBLY_INDEX_INVALID,
          "generated entity indexes produced an invalid runtime registry",
          failure);
    }
  }

  private static EntityRuntimeModel<?> model(
      EntityRuntimeModelProvider provider, GeneratedIndexReader.Entry entry) {
    try {
      EntityRuntimeModel<?> model = provider.model();
      if (model == null) {
        throw providerFailure(
            "generated entity provider " + entry.description() + " returned a null runtime model",
            null);
      }
      return model;
    } catch (SkisException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw providerFailure(
          "generated entity provider "
              + entry.description()
              + " failed to supply its runtime model",
          failure);
    } catch (LinkageError failure) {
      throw SkisConfigurationException.of(
          SkisErrorCode.ASSEMBLY_ABI_INCOMPATIBLE,
          "generated entity provider "
              + entry.description()
              + " is not link-compatible with this runtime",
          failure);
    }
  }

  private static SkisConfigurationException duplicateModel(
      String duplicate, LoadedEntity first, LoadedEntity second) {
    return SkisConfigurationException.of(
        SkisErrorCode.ASSEMBLY_MODEL_DUPLICATE,
        "generated entity providers "
            + first.entry().description()
            + " and "
            + second.entry().description()
            + " both supply "
            + duplicate);
  }

  private static EntityRuntimeModelProvider loadProvider(
      ClassLoader classLoader, GeneratedIndexReader.Entry entry) {
    String providerName = entry.providerName();
    try {
      Class<?> providerType = Class.forName(providerName, true, classLoader);
      if (!EntityRuntimeModelProvider.class.isAssignableFrom(providerType)) {
        throw providerFailure(
            "generated provider "
                + entry.description()
                + " does not implement "
                + EntityRuntimeModelProvider.class.getName(),
            null);
      }
      Object provider = providerType.getConstructor().newInstance();
      return (EntityRuntimeModelProvider) provider;
    } catch (ClassNotFoundException failure) {
      throw providerFailure(
          "generated entity provider " + entry.description() + " is missing", failure);
    } catch (NoSuchMethodException failure) {
      throw providerFailure(
          "generated entity provider " + entry.description() + " has no public no-arg constructor",
          failure);
    } catch (InstantiationException | IllegalAccessException failure) {
      throw providerFailure(
          "cannot instantiate generated entity provider " + entry.description(), failure);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      throw providerFailure(
          "generated entity provider " + entry.description() + " failed during initialization",
          cause);
    } catch (LinkageError failure) {
      throw SkisConfigurationException.of(
          SkisErrorCode.ASSEMBLY_ABI_INCOMPATIBLE,
          "generated entity provider "
              + entry.description()
              + " is not link-compatible with this runtime",
          failure);
    }
  }

  private static SkisConfigurationException providerFailure(
      String detail, @Nullable Throwable cause) {
    return SkisConfigurationException.of(SkisErrorCode.ASSEMBLY_PROVIDER_INVALID, detail, cause);
  }

  private record LoadedEntity(GeneratedIndexReader.Entry entry) {}
}
