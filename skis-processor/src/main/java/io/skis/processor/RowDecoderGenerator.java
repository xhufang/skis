package io.skis.processor;

final class RowDecoderGenerator implements EntitySourceGenerator {

  @Override
  public String suffix() {
    return "RowDecoder";
  }

  @Override
  public String render(EntityModel model) {
    String entityType = model.entityTypeName();
    String className = model.entityName() + suffix();
    String metaName = model.entityName() + "Meta";
    StringBuilder source = new StringBuilder(4096);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    source.append("import io.skis.mapping.JdbcCodecs;\n");
    source.append("import io.skis.mapping.RowDecoder;\n");
    source.append("import io.skis.mapping.RowLayout;\n");
    source.append("import io.skis.mapping.RowReadContext;\n");
    source.append("import java.sql.ResultSet;\n");
    source.append("import java.sql.SQLException;\n\n");
    source.append(SourceText.GENERATED_ANNOTATION);
    source
        .append("public final class ")
        .append(className)
        .append(" implements RowDecoder<")
        .append(entityType)
        .append("> {\n\n");
    for (PropertyModel property : model.properties()) {
      source.append("  private final int ").append(indexField(property)).append(";\n");
    }
    source.append("\n  private ").append(className).append("(RowLayout layout) {\n");
    for (PropertyModel property : model.properties()) {
      source
          .append("    this.")
          .append(indexField(property))
          .append(" = layout.requireIndex(")
          .append(metaName)
          .append('.')
          .append(property.fieldName())
          .append(".ordinal());\n");
    }
    source.append("  }\n\n");
    source
        .append("  public static ")
        .append(className)
        .append(" forLayout(RowLayout layout) {\n")
        .append("    return new ")
        .append(className)
        .append("(layout);\n")
        .append("  }\n\n");
    source
        .append("  public static ")
        .append(className)
        .append(" full(int firstColumnIndex) {\n")
        .append("    return new ")
        .append(className)
        .append("(RowLayout.contiguous(")
        .append(model.properties().size())
        .append(", firstColumnIndex));\n")
        .append("  }\n\n");
    source
        .append("  @Override\n")
        .append("  public ")
        .append(entityType)
        .append(" decode(ResultSet resultSet, RowReadContext context) throws SQLException {\n");
    switch (model.instantiation().kind()) {
      case RECORD_CONSTRUCTOR -> appendRecordConstruction(source, model);
      case BEAN -> appendBeanConstruction(source, model);
    }
    source.append("  }\n").append("}\n");
    return source.toString();
  }

  private static void appendRecordConstruction(StringBuilder source, EntityModel model) {
    source.append("    return new ").append(model.entityTypeName()).append("(\n");
    var arguments = model.instantiation().constructorArguments();
    for (int index = 0; index < arguments.size(); index++) {
      EntityInstantiationModel.ConstructorArgument argument = arguments.get(index);
      source.append("        ");
      if (argument.property() == null) {
        source.append(argument.defaultExpression());
      } else {
        source.append(readExpression(argument.property()));
      }
      source.append(index + 1 == arguments.size() ? ");\n" : ",\n");
    }
  }

  private static void appendBeanConstruction(StringBuilder source, EntityModel model) {
    source
        .append("    ")
        .append(model.entityTypeName())
        .append(" entity = new ")
        .append(model.entityTypeName())
        .append("();\n");
    for (PropertyModel property : model.properties()) {
      source
          .append("    ")
          .append(property.access().writeEntityStatement(readExpression(property)))
          .append("\n");
    }
    source.append("    return entity;\n");
  }

  private static String indexField(PropertyModel property) {
    return property.name() + "Index";
  }

  private static String readExpression(PropertyModel property) {
    String method = property.valueKind().readMethod(property.primitive());
    String index = "this." + indexField(property);
    String read = "JdbcCodecs." + method + "(resultSet, " + index + ", context)";
    if (!property.primitive() && !property.column().nullable()) {
      return "JdbcCodecs.requireReadValue(" + read + ", " + index + ")";
    }
    return read;
  }
}
