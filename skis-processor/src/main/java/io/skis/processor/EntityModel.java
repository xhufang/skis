package io.skis.processor;

import java.util.List;
import java.util.Objects;
import javax.lang.model.element.TypeElement;
import org.jspecify.annotations.Nullable;

record EntityModel(
    TypeElement type,
    String generatedPackage,
    String entityName,
    String entityTypeName,
    TableModel table,
    EntityInstantiationModel instantiation,
    List<PropertyModel> properties,
    List<PropertyModel> primaryKey,
    @Nullable PropertyModel version,
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
