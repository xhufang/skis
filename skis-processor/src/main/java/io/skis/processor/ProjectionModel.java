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
    List<ProjectionParameter> parameters) {

  ProjectionModel {
    parameters = List.copyOf(parameters);
  }

  String generatedQualifiedName() {
    return generatedPackage + "." + projectionName + "Projection";
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
}
