package io.skis.runtime;

import io.skis.core.SkisConfigurationException;
import io.skis.metadata.GeneratedModelAbi;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Reads deterministic generated-provider indexes while retaining conflict provenance. */
final class GeneratedIndexReader {

  private static final String ABI_PREFIX = "# skis-generated-abi=";

  private GeneratedIndexReader() {}

  static List<Entry> read(ClassLoader classLoader, String indexPath, String modelKind) {
    Objects.requireNonNull(classLoader, "classLoader");
    Objects.requireNonNull(indexPath, "indexPath");
    Objects.requireNonNull(modelKind, "modelKind");
    List<URL> resources = resources(classLoader, indexPath, modelKind);
    Map<String, Entry> entries = new TreeMap<>();
    for (URL resource : resources) {
      readResource(resource, indexPath, modelKind, entries);
    }
    return List.copyOf(entries.values());
  }

  private static List<URL> resources(ClassLoader classLoader, String indexPath, String modelKind) {
    try {
      Enumeration<URL> discovered = classLoader.getResources(indexPath);
      List<URL> resources = new ArrayList<>();
      while (discovered.hasMoreElements()) {
        resources.add(discovered.nextElement());
      }
      resources.sort(Comparator.comparing(URL::toExternalForm));
      return resources;
    } catch (IOException failure) {
      throw new SkisConfigurationException(
          "cannot enumerate generated " + modelKind + " indexes at " + indexPath, failure);
    }
  }

  private static void readResource(
      URL resource, String indexPath, String modelKind, Map<String, Entry> entries) {
    int abiLine = -1;
    Map<String, Entry> resourceEntries = new TreeMap<>();
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
          String value = line.strip();
          if (value.isEmpty()) {
            continue;
          }
          if (value.startsWith("#")) {
            if (value.startsWith(ABI_PREFIX)) {
              if (abiLine >= 0) {
                throw new SkisConfigurationException(
                    "generated "
                        + modelKind
                        + " index "
                        + resource
                        + " declares its generated-model ABI more than once at lines "
                        + abiLine
                        + " and "
                        + lineNumber);
              }
              requireAbi(modelKind, resource, lineNumber, value.substring(ABI_PREFIX.length()));
              abiLine = lineNumber;
            }
            continue;
          }
          if (!isBinaryName(value)) {
            throw new SkisConfigurationException(
                "invalid generated "
                    + modelKind
                    + " provider name '"
                    + value
                    + "' at "
                    + resource
                    + ":"
                    + lineNumber);
          }
          Entry current = new Entry(value, resource, lineNumber);
          Entry previous = resourceEntries.putIfAbsent(value, current);
          if (previous != null) {
            throw new SkisConfigurationException(
                "duplicate generated "
                    + modelKind
                    + " provider '"
                    + value
                    + "' is declared at "
                    + previous.location()
                    + " and "
                    + current.location());
          }
        }
      }
    } catch (SkisConfigurationException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new SkisConfigurationException(
          "cannot read generated " + modelKind + " index " + resource, failure);
    }
    if (abiLine < 0) {
      throw new SkisConfigurationException(
          "generated "
              + modelKind
              + " index "
              + resource
              + " at "
              + indexPath
              + " does not declare its generated-model ABI");
    }
    for (Entry current : resourceEntries.values()) {
      Entry previous = entries.putIfAbsent(current.providerName(), current);
      if (previous != null) {
        throw new SkisConfigurationException(
            "duplicate generated "
                + modelKind
                + " provider '"
                + current.providerName()
                + "' is declared at "
                + previous.location()
                + " and "
                + current.location());
      }
    }
  }

  private static void requireAbi(String modelKind, URL resource, int lineNumber, String value) {
    String abi = value.strip();
    try {
      GeneratedModelAbi.requireCompatible(Integer.parseInt(abi));
    } catch (NumberFormatException failure) {
      throw new SkisConfigurationException(
          "invalid generated-model ABI '" + abi + "' at " + resource + ":" + lineNumber, failure);
    } catch (IncompatibleClassChangeError failure) {
      throw new SkisConfigurationException(
          "incompatible generated-model ABI '"
              + abi
              + "' in generated "
              + modelKind
              + " index at "
              + resource
              + ":"
              + lineNumber,
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

  record Entry(String providerName, URL resource, int lineNumber) {

    Entry {
      Objects.requireNonNull(providerName, "providerName");
      Objects.requireNonNull(resource, "resource");
      if (lineNumber < 1) {
        throw new IllegalArgumentException("lineNumber must be positive");
      }
    }

    String location() {
      return resource + ":" + lineNumber;
    }

    String description() {
      return "'" + providerName + "' declared at " + location();
    }
  }
}
