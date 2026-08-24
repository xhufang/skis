package io.skis.processor;

import java.util.ArrayList;
import java.util.List;

final class BinderGenerator implements EntitySourceGenerator {

  @Override
  public String suffix() {
    return "Binder";
  }

  @Override
  public String render(EntityModel model) {
    String entityType = model.entityTypeName();
    String className = model.entityName() + suffix();
    StringBuilder source = new StringBuilder(4096);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    source.append("import io.skis.mapping.EntityMutationBinders;\n");
    source.append("import io.skis.mapping.JdbcCodecs;\n");
    source.append("import io.skis.mapping.JdbcWriteContext;\n");
    source.append("import io.skis.mapping.ParameterBinder;\n");
    source.append("import io.skis.metadata.VersionStrategy;\n");
    source.append("import java.sql.PreparedStatement;\n");
    source.append("import java.sql.SQLException;\n\n");
    source.append(SourceText.GENERATED_ANNOTATION);
    source.append("public final class ").append(className).append(" {\n\n");
    source.append("  private ").append(className).append("() {}\n");
    if (model.readOnly()) {
      source.append("}\n");
      return source.toString();
    }
    source
        .append("\n  public static final ParameterBinder<")
        .append(entityType)
        .append("> INSERT = ")
        .append(className)
        .append("::bindInsert;\n");
    source
        .append("  public static final ParameterBinder<")
        .append(entityType)
        .append("> UPSERT = INSERT;\n");
    source
        .append("  public static final ParameterBinder<")
        .append(entityType)
        .append("> UPDATE_BY_ID = ")
        .append(className)
        .append("::bindUpdateById;\n");
    source
        .append("  public static final ParameterBinder<")
        .append(entityType)
        .append("> UPDATE_BY_ID_UNCHECKED = ")
        .append(className)
        .append("::bindUpdateByIdUnchecked;\n");
    source
        .append("  public static final EntityMutationBinders<")
        .append(entityType)
        .append("> MUTATIONS = new EntityMutationBinders<>(\n")
        .append("      INSERT, UPDATE_BY_ID, UPDATE_BY_ID_UNCHECKED, ");
    if (model.version() == null) {
      source.append("null);\n\n");
    } else {
      source.append(className).append("::readVersion);\n\n");
    }

    appendMethod(
        source,
        "bindInsert",
        entityType,
        model.properties().stream().filter(property -> property.column().insertable()).toList(),
        true);
    List<PropertyModel> updateProperties = new ArrayList<>();
    model.properties().stream()
        .filter(property -> property.column().updatable())
        .filter(property -> !property.id())
        .filter(property -> !property.version())
        .forEach(updateProperties::add);
    updateProperties.addAll(model.primaryKey());
    if (model.version() != null) {
      updateProperties.add(model.version());
    }
    appendMethod(source, "bindUpdateById", entityType, updateProperties, false);
    List<PropertyModel> uncheckedUpdateProperties = new ArrayList<>(updateProperties);
    if (model.version() != null) {
      uncheckedUpdateProperties.remove(model.version());
    }
    appendMethod(
        source, "bindUpdateByIdUnchecked", entityType, uncheckedUpdateProperties, false);
    if (model.version() != null) {
      source
          .append("  private static Object readVersion(")
          .append(entityType)
          .append(" entity) throws SQLException {\n")
          .append("    return ")
          .append(model.version().access().readEntityExpression())
          .append(";\n")
          .append("  }\n\n");
    }
    source.append("}\n");
    return source.toString();
  }

  private static void appendMethod(
      StringBuilder source,
      String methodName,
      String entityType,
      List<PropertyModel> properties,
      boolean initializeVersion) {
    source
        .append("  private static int ")
        .append(methodName)
        .append("(\n")
        .append("      PreparedStatement statement,\n")
        .append("      int firstIndex,\n")
        .append("      ")
        .append(entityType)
        .append(" entity,\n")
        .append("      JdbcWriteContext context)\n")
        .append("      throws SQLException {\n")
        .append("    int index = firstIndex;\n");
    for (PropertyModel property : properties) {
      String valueExpression = property.access().readEntityExpression();
      if (initializeVersion && property.version()) {
        valueExpression =
            "VersionStrategy.NUMERIC_INCREMENT.initialize("
                + property.classLiteral()
                + ", "
                + valueExpression
                + ")";
      } else if (!property.primitive() && !property.column().nullable()) {
        valueExpression = "JdbcCodecs.requireBindValue(" + valueExpression + ", index)";
      }
      source
          .append("    JdbcCodecs.")
          .append(bindMethod(property))
          .append("(statement, index, ")
          .append(valueExpression)
          .append(", context);\n")
          .append("    index++;\n");
    }
    source.append("    return index;\n").append("  }\n\n");
  }

  private static String bindMethod(PropertyModel property) {
    return property.valueKind().bindMethod(property.primitive());
  }
}
