package io.skis.processor;

final class RuntimeModelGenerator implements EntitySourceGenerator {

  @Override
  public String suffix() {
    return "RuntimeModel";
  }

  @Override
  public String render(EntityModel model) {
    String entityType = model.entityTypeName();
    String className = model.entityName() + suffix();
    String metaName = model.entityName() + "Meta";
    String decoderName = model.entityName() + "RowDecoder";
    StringBuilder source = new StringBuilder(4096);
    source.append(SourceText.GENERATED_COMMENT);
    source.append("package ").append(model.generatedPackage()).append(";\n\n");
    source.append("import io.skis.mapping.EntityRuntimeModel;\n");
    source.append("import io.skis.mapping.EntityRuntimeModelProvider;\n");
    source.append("import io.skis.mapping.JdbcCodecs;\n");
    source.append("import io.skis.mapping.PropertyRuntime;\n");
    source.append("import java.util.List;\n\n");
    source.append(SourceText.GENERATED_ANNOTATION);
    source
        .append("public final class ")
        .append(className)
        .append(" implements EntityRuntimeModelProvider {\n\n");
    source
        .append("  public static final EntityRuntimeModel<")
        .append(entityType)
        .append("> MODEL = new EntityRuntimeModel<>(\n")
        .append("      ")
        .append(metaName)
        .append(".ENTITY,\n")
        .append("      ")
        .append(decoderName)
        .append("::forLayout,\n")
        .append("      List.of(\n");
    for (int index = 0; index < model.properties().size(); index++) {
      PropertyModel property = model.properties().get(index);
      source
          .append("          new PropertyRuntime<>(")
          .append(metaName)
          .append('.')
          .append(property.fieldName())
          .append(", JdbcCodecs.")
          .append(property.valueKind().codecConstant())
          .append(')');
      source.append(index + 1 == model.properties().size() ? ")),\n" : ",\n");
    }
    if (model.readOnly()) {
      source.append("      null);\n\n");
    } else {
      source
          .append("      ")
          .append(model.entityName())
          .append("Binder.MUTATIONS);\n\n");
    }
    source
        .append("  public ")
        .append(className)
        .append("() {}\n\n")
        .append("  @Override\n")
        .append("  public EntityRuntimeModel<")
        .append(entityType)
        .append("> model() {\n")
        .append("    return MODEL;\n")
        .append("  }\n")
        .append("}\n");
    return source.toString();
  }
}
