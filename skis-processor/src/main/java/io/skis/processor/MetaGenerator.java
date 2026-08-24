package io.skis.processor;

final class MetaGenerator implements EntitySourceGenerator {

  @Override
  public String suffix() {
    return "Meta";
  }

  @Override
  public String render(EntityModel model) {
    String entityType = model.entityTypeName();
    String className = model.entityName() + suffix();
    StringBuilder source = new StringBuilder(4096);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    source.append("import java.util.List;\n");
    source.append("import io.skis.metadata.ColumnMeta;\n");
    source.append("import io.skis.metadata.EntityMeta;\n");
    source.append("import io.skis.metadata.GeneratedModelAbi;\n");
    source.append("import io.skis.metadata.PrimaryKeyMeta;\n");
    source.append("import io.skis.metadata.PropertyMeta;\n");
    source.append("import io.skis.metadata.TableMeta;\n");
    source.append("import io.skis.metadata.VersionMeta;\n");
    source.append("import io.skis.metadata.VersionStrategy;\n\n");
    source.append(SourceText.GENERATED_ANNOTATION);
    source.append("public final class ").append(className).append(" {\n\n");
    source.append("  private ").append(className).append("() {}\n\n");
    source
        .append("  public static final int GENERATED_ABI = ")
        .append(SourceText.GENERATED_ABI)
        .append(";\n\n");
    source.append("  static {\n");
    source.append("    GeneratedModelAbi.requireCompatible(GENERATED_ABI);\n");
    source.append("  }\n\n");
    source
        .append("  public static final TableMeta TABLE = new TableMeta(")
        .append(SourceText.string(model.table().catalog()))
        .append(", ")
        .append(SourceText.string(model.table().schema()))
        .append(", ")
        .append(SourceText.string(model.table().name()))
        .append(");\n\n");
    for (PropertyModel property : model.properties()) {
      source
          .append("  public static final PropertyMeta<")
          .append(entityType)
          .append(", ")
          .append(property.typeName())
          .append("> ")
          .append(property.fieldName())
          .append(" = new PropertyMeta<>(")
          .append(property.ordinal())
          .append(", ")
          .append(SourceText.string(property.name()))
          .append(", ")
          .append(property.classLiteral())
          .append(", new ColumnMeta(")
          .append(SourceText.string(property.column().name()))
          .append(", ")
          .append(property.column().nullable())
          .append(", ")
          .append(property.column().insertable())
          .append(", ")
          .append(property.column().updatable())
          .append(", ")
          .append(property.column().length())
          .append(", ")
          .append(property.column().precision())
          .append(", ")
          .append(property.column().scale())
          .append(", ")
          .append(SourceText.string(property.column().comment()))
          .append("));\n\n");
    }
    if (model.version() != null) {
      source
          .append("  public static final VersionMeta<")
          .append(entityType)
          .append(", ")
          .append(model.version().typeName())
          .append("> VERSION = new VersionMeta<>(")
          .append(model.version().fieldName())
          .append(", VersionStrategy.NUMERIC_INCREMENT);\n\n");
    }
    source
        .append("  public static final EntityMeta<")
        .append(entityType)
        .append("> ENTITY = EntityMeta.simple(\n")
        .append("      ")
        .append(entityType)
        .append(".class,\n")
        .append("      TABLE,\n")
        .append("      List.of(");
    appendFields(source, model.properties());
    source.append("),\n");
    if (model.primaryKey().isEmpty()) {
      source.append("      null,\n");
    } else {
      source.append("      new PrimaryKeyMeta<>(List.of(");
      appendFields(source, model.primaryKey());
      source.append(")),\n");
    }
    if (model.version() != null) {
      source.append("      VERSION,\n");
    }
    source.append("      ").append(model.readOnly()).append(");\n");
    source.append("}\n");
    return source.toString();
  }

  private static void appendFields(StringBuilder source, java.util.List<PropertyModel> properties) {
    for (int index = 0; index < properties.size(); index++) {
      if (index > 0) {
        source.append(", ");
      }
      source.append(properties.get(index).fieldName());
    }
  }
}
