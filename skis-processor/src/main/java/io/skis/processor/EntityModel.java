package io.skis.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

record EntityModel(
    TypeElement type,
    String packageName,
    String generatedPackage,
    String entityName,
    String entityTypeName,
    TableModel table,
    List<RecordComponentModel> components,
    List<PropertyModel> properties,
    List<PropertyModel> primaryKey,
    PropertyModel version,
    boolean readOnly) {

  EntityModel {
    components = List.copyOf(components);
    properties = List.copyOf(properties);
    primaryKey = List.copyOf(primaryKey);
  }

  String generatedQualifiedName(String suffix) {
    return generatedPackage + "." + entityName + suffix;
  }
}
