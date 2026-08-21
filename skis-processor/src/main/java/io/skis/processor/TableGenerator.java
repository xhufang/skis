package io.skis.processor;

final class TableGenerator implements EntitySourceGenerator {

  @Override
  public String suffix() {
    return "Table";
  }

  @Override
  public String render(EntityModel model) {
    String entityType = model.entityTypeName();
    String className = model.entityName() + suffix();
    String metaName = model.entityName() + "Meta";
    StringBuilder source = new StringBuilder(3072);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    source.append("import io.skis.sql.ast.ColumnExpression;\n");
    source.append("import io.skis.sql.ast.Identifier;\n");
    source.append("import io.skis.sql.ast.TableExpression;\n");
    source.append("import org.jspecify.annotations.NonNull;\n\n");
    source.append(SourceText.GENERATED_ANNOTATION);
    source.append("public final class ")
        .append(className)
        .append(" extends TableExpression<")
        .append(entityType)
        .append("> {\n\n");
    source.append("  public static final ")
        .append(className)
        .append(" ")
        .append(SourceText.constantName(model.entityName()))
        .append(" = new ")
        .append(className)
        .append("();\n\n");
    for (PropertyModel property : model.properties()) {
      source.append("  private final ColumnExpression<")
          .append(entityType)
          .append(", ")
          .append(property.typeName())
          .append("> ")
          .append(property.name())
          .append("Column = column(")
          .append(metaName)
          .append('.')
          .append(property.fieldName())
          .append(");\n");
    }
    source.append("\n  private ").append(className).append("() {\n")
        .append("    super(")
        .append(metaName)
        .append(".ENTITY);\n")
        .append("  }\n\n")
        .append("  private ")
        .append(className)
        .append("(@NonNull Identifier alias) {\n")
        .append("    super(")
        .append(metaName)
        .append(".ENTITY, alias);\n")
        .append("  }\n\n");
    for (PropertyModel property : model.properties()) {
      source.append("  public ColumnExpression<")
          .append(entityType)
          .append(", ")
          .append(property.typeName())
          .append("> ")
          .append(property.tableMethodName())
          .append("() {\n")
          .append("    return ")
          .append(property.name())
          .append("Column;\n")
          .append("  }\n\n");
    }
    source.append("  @Override\n")
        .append("  public ")
        .append(className)
        .append(" as(@NonNull String alias) {\n")
        .append("    return new ")
        .append(className)
        .append("(Identifier.of(alias));\n")
        .append("  }\n\n")
        .append("  @Override\n")
        .append("  public ")
        .append(className)
        .append(" as(@NonNull Identifier alias) {\n")
        .append("    return new ")
        .append(className)
        .append("(alias);\n")
        .append("  }\n")
        .append("}\n");
    return source.toString();
  }
}
