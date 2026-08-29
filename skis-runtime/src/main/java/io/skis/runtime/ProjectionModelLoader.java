package io.skis.runtime;

import io.skis.core.SkisConfigurationException;
import io.skis.query.Projection;
import io.skis.query.ProjectionProvider;
import io.skis.query.ProjectionRegistry;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads generated projection providers once from deterministic indexes. */
final class ProjectionModelLoader {

  static final String INDEX_PATH = "META-INF/skis/projections.idx";

  private ProjectionModelLoader() {}

  static ProjectionRegistry load(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "classLoader");
    List<GeneratedIndexReader.Entry> entries =
        GeneratedIndexReader.read(classLoader, INDEX_PATH, "projection");
    List<Projection<?, ?>> projections = new ArrayList<>(entries.size());
    Map<Class<?>, LoadedProjection> byResultType = new IdentityHashMap<>();
    for (GeneratedIndexReader.Entry entry : entries) {
      ProjectionProvider provider = loadProvider(classLoader, entry);
      Projection<?, ?> projection = projection(provider, entry);
      LoadedProjection loaded = new LoadedProjection(entry);
      LoadedProjection sameResultType = byResultType.putIfAbsent(projection.resultType(), loaded);
      if (sameResultType != null) {
        throw new SkisConfigurationException(
            "generated projection providers "
                + sameResultType.entry().description()
                + " and "
                + loaded.entry().description()
                + " both supply projection result type '"
                + projection.resultType().getTypeName()
                + "'");
      }
      projections.add(projection);
    }
    try {
      return ProjectionRegistry.of(projections);
    } catch (IllegalArgumentException failure) {
      throw new SkisConfigurationException(
          "generated projection indexes produced an invalid projection registry", failure);
    }
  }

  private static Projection<?, ?> projection(
      ProjectionProvider provider, GeneratedIndexReader.Entry entry) {
    try {
      Projection<?, ?> projection = provider.projection();
      if (projection == null) {
        throw new SkisConfigurationException(
            "generated projection provider " + entry.description() + " returned a null projection");
      }
      return projection;
    } catch (SkisConfigurationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new SkisConfigurationException(
          "generated projection provider "
              + entry.description()
              + " failed to supply its projection",
          failure);
    } catch (LinkageError failure) {
      throw new SkisConfigurationException(
          "generated projection provider "
              + entry.description()
              + " is not link-compatible with this runtime",
          failure);
    }
  }

  private static ProjectionProvider loadProvider(
      ClassLoader classLoader, GeneratedIndexReader.Entry entry) {
    String providerName = entry.providerName();
    try {
      Class<?> providerType = Class.forName(providerName, true, classLoader);
      if (!ProjectionProvider.class.isAssignableFrom(providerType)) {
        throw new SkisConfigurationException(
            "generated provider "
                + entry.description()
                + " does not implement "
                + ProjectionProvider.class.getName());
      }
      Object provider = providerType.getConstructor().newInstance();
      return (ProjectionProvider) provider;
    } catch (ClassNotFoundException failure) {
      throw new SkisConfigurationException(
          "generated projection provider " + entry.description() + " is missing", failure);
    } catch (NoSuchMethodException failure) {
      throw new SkisConfigurationException(
          "generated projection provider "
              + entry.description()
              + " has no public no-arg constructor",
          failure);
    } catch (InstantiationException | IllegalAccessException failure) {
      throw new SkisConfigurationException(
          "cannot instantiate generated projection provider " + entry.description(), failure);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      throw new SkisConfigurationException(
          "generated projection provider " + entry.description() + " failed during initialization",
          cause);
    } catch (LinkageError failure) {
      throw new SkisConfigurationException(
          "generated projection provider "
              + entry.description()
              + " is not link-compatible with this runtime",
          failure);
    }
  }

  private record LoadedProjection(GeneratedIndexReader.Entry entry) {}
}
