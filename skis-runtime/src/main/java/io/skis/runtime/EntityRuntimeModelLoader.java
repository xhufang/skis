package io.skis.runtime;

import io.skis.core.SkisConfigurationException;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeModelProvider;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.metadata.EntityMeta;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
      throw new SkisConfigurationException(
          "generated entity indexes produced an invalid runtime registry", failure);
    }
  }

  private static EntityRuntimeModel<?> model(
      EntityRuntimeModelProvider provider, GeneratedIndexReader.Entry entry) {
    try {
      EntityRuntimeModel<?> model = provider.model();
      if (model == null) {
        throw new SkisConfigurationException(
            "generated entity provider " + entry.description() + " returned a null runtime model");
      }
      return model;
    } catch (SkisConfigurationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new SkisConfigurationException(
          "generated entity provider "
              + entry.description()
              + " failed to supply its runtime model",
          failure);
    } catch (LinkageError failure) {
      throw new SkisConfigurationException(
          "generated entity provider "
              + entry.description()
              + " is not link-compatible with this runtime",
          failure);
    }
  }

  private static SkisConfigurationException duplicateModel(
      String duplicate, LoadedEntity first, LoadedEntity second) {
    return new SkisConfigurationException(
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
        throw new SkisConfigurationException(
            "generated provider "
                + entry.description()
                + " does not implement "
                + EntityRuntimeModelProvider.class.getName());
      }
      Object provider = providerType.getConstructor().newInstance();
      return (EntityRuntimeModelProvider) provider;
    } catch (ClassNotFoundException failure) {
      throw new SkisConfigurationException(
          "generated entity provider " + entry.description() + " is missing", failure);
    } catch (NoSuchMethodException failure) {
      throw new SkisConfigurationException(
          "generated entity provider " + entry.description() + " has no public no-arg constructor",
          failure);
    } catch (InstantiationException | IllegalAccessException failure) {
      throw new SkisConfigurationException(
          "cannot instantiate generated entity provider " + entry.description(), failure);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      throw new SkisConfigurationException(
          "generated entity provider " + entry.description() + " failed during initialization",
          cause);
    } catch (LinkageError failure) {
      throw new SkisConfigurationException(
          "generated entity provider "
              + entry.description()
              + " is not link-compatible with this runtime",
          failure);
    }
  }

  private record LoadedEntity(GeneratedIndexReader.Entry entry) {}
}
