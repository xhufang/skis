package io.skis.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

record ProjectionModel(
    TypeElement type,
    String generatedPackage,
    String projectionName,
    String projectionTypeName,
    EntityModel entity,
    List<ProjectionParameter> parameters) {

  ProjectionModel {
    parameters = List.copyOf(parameters);
  }

  String generatedQualifiedName() {
    return generatedPackage + "." + projectionName + "Projection";
  }

  record ProjectionParameter(String name, String typeName, PropertyModel property) {}
}
