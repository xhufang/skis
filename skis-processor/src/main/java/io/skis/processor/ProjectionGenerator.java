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
    EntityModel entity = model.entity();
    String entityMetaType = entity.generatedPackage() + "." + entity.entityName() + "Meta";
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
    source.append("import io.skis.query.ProjectionProvider;\n");
    source.append("import java.util.List;\n\n");
    source
        .append("@javax.annotation.processing.Generated(\n")
        .append("    value = \"io.skis.processor.SkisProjectionProcessor\",\n")
        .append("    comments = \"Projection ABI ")
        .append(SourceText.GENERATED_ABI)
        .append("\")\n");
    source
        .append("public final class ")
        .append(className)
        .append(" implements ProjectionProvider {\n\n");
    source
        .append("  private static final Projection.Mapping<")
        .append(model.projectionTypeName())
        .append("> MAPPING =\n")
        .append("      Projection.mapping(")
        .append(className)
        .append(".class);\n\n");
    source
        .append("  private static final Projection<")
        .append(entity.entityTypeName())
        .append(", ")
        .append(model.projectionTypeName())
        .append("> PROJECTION =\n")
        .append("      Projection.generated(\n")
        .append("          ")
        .append(model.projectionTypeName())
        .append(".class,\n")
        .append("          ")
        .append(entityMetaType)
        .append(".ENTITY,\n")
        .append("          MAPPING,\n")
        .append("          List.of(\n");
    for (int index = 0; index < parameters.size(); index++) {
      source
          .append("              ")
          .append(entityMetaType)
          .append('.')
          .append(parameters.get(index).property().fieldName())
          .append(index + 1 == parameters.size() ? "),\n" : ",\n");
    }
    source.append("          ").append(readersName).append(" -> {\n");
    for (int index = 0; index < parameters.size(); index++) {
      ProjectionModel.ProjectionParameter parameter = parameters.get(index);
      source
          .append("            Projection.ValueReader<")
          .append(parameter.typeName())
          .append("> ")
          .append(valueNames[index])
          .append(" =\n")
          .append("                ")
          .append(readersName)
          .append(".reader(")
          .append(index)
          .append(", ")
          .append(entityMetaType)
          .append('.')
          .append(parameter.property().fieldName())
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
      source
          .append("                    ")
          .append(valueNames[index])
          .append(".read(")
          .append(resultSetName)
          .append(", ")
          .append(contextName)
          .append(")")
          .append(index + 1 == parameters.size() ? ");\n" : ",\n");
    }
    source.append("          });\n\n");
    source.append("  public ").append(className).append("() {}\n\n");
    source
        .append("  @Override\n")
        .append("  public Projection<")
        .append(entity.entityTypeName())
        .append(", ")
        .append(model.projectionTypeName())
        .append("> projection() {\n")
        .append("    return PROJECTION;\n")
        .append("  }\n");
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
