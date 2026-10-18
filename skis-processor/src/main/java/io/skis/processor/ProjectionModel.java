package io.skis.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

record ProjectionModel(
    TypeElement type,
    String generatedPackage,
    String projectionName,
    String projectionTypeName,
    String projectionBinaryName,
    String constructorDescriptor,
    ProjectionKind kind,
    List<ProjectionParameter> parameters,
    List<ProjectionSource> sources) {

  ProjectionModel {
    parameters = List.copyOf(parameters);
    sources = List.copyOf(sources);
    if (kind == ProjectionKind.REUSABLE && !sources.isEmpty()) {
      throw new IllegalArgumentException("a reusable projection must not declare sources");
    }
    if (kind == ProjectionKind.SPECIAL && sources.size() != parameters.size()) {
      throw new IllegalArgumentException(
          "a special projection must declare one source per constructor parameter");
    }
  }

  String generatedQualifiedName() {
    return generatedPackage + "." + projectionName + "Pro";
  }

  String mappingId() {
    StringBuilder identity =
        new StringBuilder("skis-projection:")
            .append(SourceText.GENERATED_ABI)
            .append(':')
            .append(projectionBinaryName)
            .append(':')
            .append(constructorDescriptor);
    for (ProjectionParameter parameter : parameters) {
      identity
          .append(':')
          .append(parameter.name())
          .append('=')
          .append(parameter.erasedTypeName())
          .append(parameter.nullable() ? '?' : '!');
    }
    return identity.toString();
  }

  record ProjectionParameter(
      String name,
      String typeName,
      String erasedTypeName,
      String classLiteral,
      boolean primitive,
      String constructorTypeName,
      boolean nullable) {}

  record ProjectionSource(String metaTypeName, String metaFieldName, String alias) {}

  enum ProjectionKind {
    REUSABLE,
    SPECIAL
  }
}
