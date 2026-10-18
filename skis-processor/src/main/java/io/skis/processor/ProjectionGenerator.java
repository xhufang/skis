package io.skis.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.tools.JavaFileObject;

final class ProjectionGenerator {

  String render(ProjectionModel model) {
    String className = model.projectionName() + "Pro";
    List<ProjectionModel.ProjectionParameter> parameters = model.parameters();
    Set<String> usedNames = new HashSet<>();
    parameters.forEach(parameter -> usedNames.add(parameter.name()));
    String readersName = uniqueName("$skisReaders", usedNames);
    String resultSetName = uniqueName("$skisResultSet", usedNames);
    String contextName = uniqueName("$skisContext", usedNames);
    String[] valueNames = new String[parameters.size()];
    for (int index = 0; index < parameters.size(); index++) {
      valueNames[index] = uniqueName("$skisValue" + index, usedNames);
    }
    StringBuilder source = new StringBuilder(4096 + parameters.size() * 320);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    if (model.kind() == ProjectionModel.ProjectionKind.REUSABLE) {
      source.append("import io.skis.query.NonNullSelectable;\n");
      source.append("import io.skis.query.ProjectionSelection;\n");
      source.append("import io.skis.query.Selectable;\n");
    } else {
      source.append("import io.skis.query.ProjectionType;\n");
      source.append("import io.skis.query.spi.ProjectionSource;\n");
    }
    source.append("import io.skis.query.spi.ProjectionMapping;\n");
    source.append("import io.skis.sql.ast.Nullability;\n");
    source.append("import java.util.List;\n\n");
    source
        .append("@javax.annotation.processing.Generated(\n")
        .append("    value = \"io.skis.processor.SkisProjectionProcessor\",\n")
        .append("    comments = \"Projection ABI ")
        .append(SourceText.GENERATED_ABI)
        .append("\")\n");
    source.append("public final class ").append(className);
    if (model.kind() == ProjectionModel.ProjectionKind.SPECIAL) {
      source.append(" implements ProjectionType<").append(model.projectionTypeName()).append(">");
    }
    source.append(" {\n\n");
    source
        .append("  private static final ProjectionMapping<")
        .append(model.projectionTypeName())
        .append("> MAPPING =\n")
        .append("      ProjectionMapping.generated(\n")
        .append("          ")
        .append(SourceText.GENERATED_ABI)
        .append(",\n")
        .append("          ")
        .append(model.projectionTypeName())
        .append(".class,\n")
        .append("          ")
        .append(SourceText.string(model.mappingId()))
        .append(",\n")
        .append("          List.of(\n");
    for (int index = 0; index < parameters.size(); index++) {
      ProjectionModel.ProjectionParameter parameter = parameters.get(index);
      source
          .append("              new ProjectionMapping.Parameter(")
          .append(index)
          .append(", ")
          .append(SourceText.string(parameter.name()))
          .append(", ")
          .append(parameter.classLiteral())
          .append(", Nullability.")
          .append(parameter.nullable() ? "NULLABLE" : "NON_NULL")
          .append(", ")
          .append(index)
          .append(index + 1 == parameters.size() ? ")),\n" : "),\n");
    }
    source.append("          ").append(readersName).append(" -> {\n");
    for (int index = 0; index < parameters.size(); index++) {
      ProjectionModel.ProjectionParameter parameter = parameters.get(index);
      source
          .append("            ProjectionMapping.ValueReader<")
          .append(parameter.typeName())
          .append("> ")
          .append(valueNames[index])
          .append(" =\n")
          .append("                ")
          .append(readersName)
          .append(".reader(")
          .append(index)
          .append(", ")
          .append(parameter.classLiteral())
          .append(");\n");
    }
    source
        .append("            return (")
        .append(resultSetName)
        .append(", ")
        .append(contextName)
        .append(") ->\n")
        .append("                new ")
        .append(model.projectionTypeName())
        .append("(\n");
    for (int index = 0; index < parameters.size(); index++) {
      ProjectionModel.ProjectionParameter parameter = parameters.get(index);
      source
          .append("                    ")
          .append(parameter.primitive() ? "(" + parameter.constructorTypeName() + ") " : "")
          .append(valueNames[index])
          .append(".read(")
          .append(resultSetName)
          .append(", ")
          .append(contextName)
          .append(")")
          .append(index + 1 == parameters.size() ? ");\n" : ",\n");
    }
    source.append("          });\n\n");
    if (model.kind() == ProjectionModel.ProjectionKind.SPECIAL) {
      appendSpecialContract(source, model, className);
    } else {
      appendReusableContract(source, model, className);
    }
    source.append("}\n");
    return source.toString();
  }

  private static void appendReusableContract(
      StringBuilder source, ProjectionModel model, String className) {
    List<ProjectionModel.ProjectionParameter> parameters = model.parameters();
    source.append("  private ").append(className).append("() {}\n\n");
    source
        .append("  public static ProjectionSelection<")
        .append(model.projectionTypeName())
        .append("> of(\n");
    for (int index = 0; index < parameters.size(); index++) {
      ProjectionModel.ProjectionParameter parameter = parameters.get(index);
      source
          .append("      ")
          .append(parameter.nullable() ? "Selectable<" : "NonNullSelectable<")
          .append(parameter.typeName())
          .append("> ")
          .append(parameter.name())
          .append(index + 1 == parameters.size() ? ") {\n" : ",\n");
    }
    source.append("    return ").append(className).append(".MAPPING.bind(");
    for (int index = 0; index < parameters.size(); index++) {
      source
          .append(parameters.get(index).name())
          .append(index + 1 == parameters.size() ? ");\n" : ", ");
    }
    source.append("  }\n");
  }

  private static void appendSpecialContract(
      StringBuilder source, ProjectionModel model, String className) {
    source.append("  private static final List<ProjectionSource> SOURCES =\n");
    source.append("      List.of(\n");
    for (int index = 0; index < model.sources().size(); index++) {
      ProjectionModel.ProjectionSource projectionSource = model.sources().get(index);
      source
          .append("          ProjectionSource.of(")
          .append(projectionSource.metaTypeName())
          .append('.')
          .append(projectionSource.metaFieldName());
      if (!projectionSource.alias().isEmpty()) {
        source.append(", ").append(SourceText.string(projectionSource.alias()));
      }
      source.append(index + 1 == model.sources().size() ? "));\n\n" : "),\n");
    }
    source
        .append("  private static final ")
        .append(className)
        .append(" INSTANCE = new ")
        .append(className)
        .append("();\n\n")
        .append("  private ")
        .append(className)
        .append("() {}\n\n")
        .append("  public static ")
        .append(className)
        .append(" all() {\n")
        .append("    return INSTANCE;\n")
        .append("  }\n\n")
        .append("  @Override\n")
        .append("  public ProjectionMapping<")
        .append(model.projectionTypeName())
        .append("> mapping() {\n")
        .append("    return MAPPING;\n")
        .append("  }\n\n")
        .append("  @Override\n")
        .append("  public List<ProjectionSource> sources() {\n")
        .append("    return SOURCES;\n")
        .append("  }\n");
  }

  void generate(ProjectionModel model, Filer filer) throws IOException {
    JavaFileObject source = filer.createSourceFile(model.generatedQualifiedName(), model.type());
    try (Writer writer = source.openWriter()) {
      writer.write(render(model));
    }
  }

  private static String uniqueName(String candidate, Set<String> usedNames) {
    String result = candidate;
    while (!usedNames.add(result)) {
      result += "_";
    }
    return result;
  }
}
