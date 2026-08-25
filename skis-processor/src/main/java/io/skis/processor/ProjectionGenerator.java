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
    String className = model.projectionName() + "Projection";
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
    StringBuilder source = new StringBuilder(4096 + parameters.size() * 256);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    source.append("import io.skis.query.Projection;\n");
    source.append("import io.skis.query.QueryColumn;\n");
    source.append("import java.util.List;\n\n");
    source
        .append("@javax.annotation.processing.Generated(\n")
        .append("    value = \"io.skis.processor.SkisProjectionProcessor\",\n")
        .append("    comments = \"Projection ABI 1\")\n");
    source.append("public final class ").append(className).append(" {\n\n");
    source
        .append("  private static final Projection.Mapping<")
        .append(model.projectionTypeName())
        .append("> MAPPING =\n")
        .append("      Projection.mapping(")
        .append(className)
        .append(".class);\n\n");
    source.append("  private ").append(className).append("() {}\n\n");
    source
        .append("  public static <E> Projection<E, ")
        .append(model.projectionTypeName())
        .append("> of(\n");
    for (int index = 0; index < parameters.size(); index++) {
      ProjectionModel.ProjectionParameter parameter = parameters.get(index);
      source
          .append("      QueryColumn<E, ")
          .append(parameter.typeName())
          .append("> ")
          .append(parameter.name())
          .append(index + 1 == parameters.size() ? ") {\n" : ",\n");
    }
    for (ProjectionModel.ProjectionParameter parameter : parameters) {
      if (parameter.primitive()) {
        appendPrimitiveNullabilityCheck(source, parameter);
      }
    }
    source.append("    return Projection.generated(\n");
    source.append("        MAPPING,\n");
    source.append("        List.of(\n");
    for (int index = 0; index < parameters.size(); index++) {
      source
          .append("            ")
          .append(parameters.get(index).name())
          .append(index + 1 == parameters.size() ? "),\n" : ",\n");
    }
    source.append("        ").append(readersName).append(" -> {\n");
    for (int index = 0; index < parameters.size(); index++) {
      ProjectionModel.ProjectionParameter parameter = parameters.get(index);
      source
          .append("          Projection.ValueReader<")
          .append(parameter.typeName())
          .append("> ")
          .append(valueNames[index])
          .append(" =\n")
          .append("              ")
          .append(readersName)
          .append(".reader(")
          .append(index)
          .append(", ")
          .append(parameter.name())
          .append(");\n");
    }
    source
        .append("          return (")
        .append(resultSetName)
        .append(", ")
        .append(contextName)
        .append(") ->\n")
        .append("              new ")
        .append(model.projectionTypeName())
        .append("(\n");
    for (int index = 0; index < parameters.size(); index++) {
      source
          .append("                  ")
          .append(valueNames[index])
          .append(".read(")
          .append(resultSetName)
          .append(", ")
          .append(contextName)
          .append(")")
          .append(index + 1 == parameters.size() ? ");\n" : ",\n");
    }
    source.append("        });\n");
    source.append("  }\n");
    source.append("}\n");
    return source.toString();
  }

  void generate(ProjectionModel model, Filer filer) throws IOException {
    JavaFileObject source = filer.createSourceFile(model.generatedQualifiedName(), model.type());
    try (Writer writer = source.openWriter()) {
      writer.write(render(model));
    }
  }

  private static void appendPrimitiveNullabilityCheck(
      StringBuilder source, ProjectionModel.ProjectionParameter parameter) {
    source.append("    if (").append(parameter.name()).append(".nullable()) {\n");
    source
        .append("      throw new io.skis.query.QueryValidationException(\n")
        .append("          \"primitive projection parameter '")
        .append(parameter.name())
        .append("' cannot use nullable column '\"\n")
        .append("              + ")
        .append(parameter.name())
        .append(".property().name()\n")
        .append("              + \"'\");\n")
        .append("    }\n");
  }

  private static String uniqueName(String candidate, Set<String> usedNames) {
    String result = candidate;
    while (!usedNames.add(result)) {
      result += "_";
    }
    return result;
  }
}
