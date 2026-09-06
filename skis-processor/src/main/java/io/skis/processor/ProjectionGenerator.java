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
    StringBuilder source = new StringBuilder(4096 + parameters.size() * 320);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    source.append("import io.skis.query.NonNullSelectable;\n");
    source.append("import io.skis.query.ProjectionMapping;\n");
    source.append("import io.skis.query.ProjectionSelection;\n");
    source.append("import io.skis.query.Selectable;\n");
    source.append("import io.skis.sql.ast.Nullability;\n");
    source.append("import java.util.List;\n\n");
    source
        .append("@javax.annotation.processing.Generated(\n")
        .append("    value = \"io.skis.processor.SkisProjectionProcessor\",\n")
        .append("    comments = \"Projection ABI ")
        .append(SourceText.GENERATED_ABI)
        .append("\")\n");
    source.append("public final class ").append(className).append(" {\n\n");
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
    source.append("}\n");
    return source.toString();
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
