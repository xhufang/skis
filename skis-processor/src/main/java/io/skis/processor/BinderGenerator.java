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
    source.append("import io.skis.mapping.JdbcCodecs;\n");
    source.append("import io.skis.mapping.JdbcWriteContext;\n");
    source.append("import io.skis.mapping.ParameterBinder;\n");
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
        .append("::bindUpdateById;\n\n");

    appendMethod(
        source,
        "bindInsert",
        entityType,
        model.properties().stream().filter(property -> property.column().insertable()).toList());
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
    appendMethod(source, "bindUpdateById", entityType, updateProperties);
    source.append("}\n");
    return source.toString();
  }

  private static void appendMethod(
      StringBuilder source, String methodName, String entityType, List<PropertyModel> properties) {
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
      if (!property.primitive() && !property.column().nullable()) {
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
