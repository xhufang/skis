package io.skis.processor;

import java.util.List;
import java.util.Objects;
import javax.lang.model.element.TypeElement;

record EntityModel(
    TypeElement type,
    String generatedPackage,
    String entityName,
    String entityTypeName,
    TableModel table,
    EntityInstantiationModel instantiation,
    List<PropertyModel> properties,
    List<PropertyModel> primaryKey,
    PropertyModel version,
    boolean readOnly) {

  EntityModel {
    Objects.requireNonNull(instantiation, "instantiation");
    properties = List.copyOf(properties);
    primaryKey = List.copyOf(primaryKey);
  }

  String generatedQualifiedName(String suffix) {
    return generatedPackage + "." + entityName + suffix;
  }
}
