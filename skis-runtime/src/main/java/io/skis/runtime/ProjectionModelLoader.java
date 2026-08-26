package io.skis.runtime;

import io.skis.core.SkisConfigurationException;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.query.Projection;
import io.skis.query.ProjectionProvider;
import io.skis.query.ProjectionRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Loads generated projection providers once from deterministic indexes. */
final class ProjectionModelLoader {

  static final String INDEX_PATH = "META-INF/skis/projections.idx";
  private static final String ABI_PREFIX = "# skis-generated-abi=";

  private ProjectionModelLoader() {}

  static ProjectionRegistry load(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "classLoader");
    Set<String> providerNames = readProviderNames(classLoader);
    List<Projection<?, ?>> projections = new ArrayList<>(providerNames.size());
    for (String providerName : providerNames) {
      ProjectionProvider provider = loadProvider(classLoader, providerName);
      try {
        Projection<?, ?> projection = provider.projection();
        if (projection == null) {
          throw new SkisConfigurationException(
              "generated projection provider '" + providerName + "' returned a null projection");
        }
        projections.add(projection);
      } catch (SkisConfigurationException failure) {
        throw failure;
      } catch (RuntimeException failure) {
        throw new SkisConfigurationException(
            "generated projection provider '" + providerName + "' failed to supply its projection",
            failure);
      } catch (LinkageError failure) {
        throw new SkisConfigurationException(
            "generated projection provider '"
                + providerName
                + "' is not link-compatible with this runtime",
            failure);
      }
    }
    try {
      return ProjectionRegistry.of(projections);
    } catch (IllegalArgumentException failure) {
      throw new SkisConfigurationException(
          "generated projection indexes produced an invalid projection registry", failure);
    }
  }

  private static Set<String> readProviderNames(ClassLoader classLoader) {
    Set<String> providerNames = new TreeSet<>();
    try {
      Enumeration<URL> resources = classLoader.getResources(INDEX_PATH);
      while (resources.hasMoreElements()) {
        readIndex(resources.nextElement(), providerNames);
      }
      return providerNames;
    } catch (IOException failure) {
      throw new SkisConfigurationException(
          "cannot enumerate generated projection indexes at " + INDEX_PATH, failure);
    }
  }

  private static void readIndex(URL resource, Set<String> providerNames) {
    boolean abiSeen = false;
    try {
      URLConnection connection = resource.openConnection();
      connection.setUseCaches(false);
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
          lineNumber++;
          String entry = line.strip();
          if (entry.isEmpty()) {
            continue;
          }
          if (entry.startsWith("#")) {
            if (entry.startsWith(ABI_PREFIX)) {
              requireAbi(resource, lineNumber, entry.substring(ABI_PREFIX.length()));
              abiSeen = true;
            }
            continue;
          }
          if (!isBinaryName(entry)) {
            throw new SkisConfigurationException(
                "invalid generated projection provider name '"
                    + entry
                    + "' at "
                    + resource
                    + ":"
                    + lineNumber);
          }
          providerNames.add(entry);
        }
      }
    } catch (IOException failure) {
      throw new SkisConfigurationException(
          "cannot read generated projection index " + resource, failure);
    }
    if (!abiSeen) {
      throw new SkisConfigurationException(
          "generated projection index " + resource + " does not declare its generated-model ABI");
    }
  }

  private static void requireAbi(URL resource, int lineNumber, String value) {
    try {
      GeneratedModelAbi.requireCompatible(Integer.parseInt(value));
    } catch (NumberFormatException failure) {
      throw new SkisConfigurationException(
          "invalid generated-model ABI '" + value + "' at " + resource + ":" + lineNumber, failure);
    } catch (IncompatibleClassChangeError failure) {
      throw new SkisConfigurationException(
          "incompatible generated-model ABI '" + value + "' at " + resource + ":" + lineNumber,
          failure);
    }
  }

  private static ProjectionProvider loadProvider(ClassLoader classLoader, String providerName) {
    try {
      Class<?> providerType = Class.forName(providerName, true, classLoader);
      if (!ProjectionProvider.class.isAssignableFrom(providerType)) {
        throw new SkisConfigurationException(
            "generated provider '"
                + providerName
                + "' does not implement "
                + ProjectionProvider.class.getName());
      }
      Object provider = providerType.getConstructor().newInstance();
      return (ProjectionProvider) provider;
    } catch (ClassNotFoundException failure) {
      throw new SkisConfigurationException(
          "generated projection provider '" + providerName + "' is missing", failure);
    } catch (NoSuchMethodException failure) {
      throw new SkisConfigurationException(
          "generated projection provider '" + providerName + "' has no public no-arg constructor",
          failure);
    } catch (InstantiationException | IllegalAccessException failure) {
      throw new SkisConfigurationException(
          "cannot instantiate generated projection provider '" + providerName + "'", failure);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      throw new SkisConfigurationException(
          "generated projection provider '" + providerName + "' failed during initialization",
          cause);
    } catch (LinkageError failure) {
      throw new SkisConfigurationException(
          "generated projection provider '"
              + providerName
              + "' is not link-compatible with this runtime",
          failure);
    }
  }

  private static boolean isBinaryName(String value) {
    if (value.isEmpty()) {
      return false;
    }
    int segmentStart = 0;
    for (int index = 0; index <= value.length(); index++) {
      if (index == value.length() || value.charAt(index) == '.') {
        if (index == segmentStart || !Character.isJavaIdentifierStart(value.charAt(segmentStart))) {
          return false;
        }
        for (int character = segmentStart + 1; character < index; character++) {
          if (!Character.isJavaIdentifierPart(value.charAt(character))) {
            return false;
          }
        }
        segmentStart = index + 1;
      }
    }
    return true;
  }
}
