package io.skis.processor;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Transient;
import io.skis.annotations.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

final class EntityModelScanner {

  private static final Set<String> RESERVED_META_FIELDS =
      Set.of("TABLE", "ENTITY", "VERSION", "PRIMARY_KEY", "GENERATED_ABI");
  private static final Set<String> RESERVED_TABLE_METHODS = Set.of("as", "alias", "entity");

  private final Elements elements;
  private final Types types;

  EntityModelScanner(Elements elements, Types types) {
    this.elements = elements;
    this.types = types;
  }

  EntityModel scan(TypeElement type) throws EntityScanException, EntityScanDeferredException {
    validateEntityShape(type);
    PackageElement packageElement = elements.getPackageOf(type);
    String packageName = packageElement.getQualifiedName().toString();
    if (packageName.isEmpty()) {
      throw failure("SKIS005", "an entity must declare a package", type);
    }
    SkisEntity entity = type.getAnnotation(SkisEntity.class);
    if (entity == null) {
      throw failure("SKIS001", "an entity must be annotated with @SkisEntity", type);
    }
    Table table = type.getAnnotation(Table.class);
    String tableName = table == null ? "" : table.name();
    String schema = table == null ? "" : table.schema();
    String catalog = table == null ? "" : table.catalog();
    if (tableName.isEmpty()) {
      tableName = snakeCase(type.getSimpleName().toString());
    }
    List<RecordComponentModel> components = new ArrayList<>(type.getRecordComponents().size());
    List<PropertyModel> properties = new ArrayList<>(type.getRecordComponents().size());
    Set<String> usedFieldNames = new HashSet<>(RESERVED_META_FIELDS);
    for (RecordComponentElement component : type.getRecordComponents()) {
      if (containsUnresolvedType(component.asType())) {
        throw new EntityScanDeferredException(
            "property '"
                + component.getSimpleName()
                + "' still has unresolved type '"
                + component.asType()
                + "'");
      }
      Id id = component.getAnnotation(Id.class);
      Version version = component.getAnnotation(Version.class);
      Transient ignored = component.getAnnotation(Transient.class);
      if (ignored != null && (id != null || version != null)) {
        throw failure("SKIS007", "@Transient cannot be combined with @Id or @Version", component);
      }
      if (ignored != null) {
        components.add(
            new RecordComponentModel(
                component.getSimpleName().toString(),
                component,
                null,
                defaultExpression(component.asType())));
        continue;
      }
      Column annotation = component.getAnnotation(Column.class);
      String columnName = annotation == null ? "" : annotation.name();
      if (columnName.isEmpty()) {
        columnName = snakeCase(component.getSimpleName().toString());
      }
      boolean explicitlyNullable = isExplicitlyNullable(component);
      boolean nullable =
          id == null && version == null && (annotation == null || annotation.nullable());
      ColumnModel column =
          new ColumnModel(
              columnName,
              nullable,
              explicitlyNullable,
              annotation == null || annotation.insertable(),
              annotation == null || annotation.updatable(),
              annotation == null ? 255 : annotation.length(),
              annotation == null ? 0 : annotation.precision(),
              annotation == null ? 0 : annotation.scale(),
              annotation == null ? "" : annotation.comment());
      TypeDescriptor descriptor = describe(component.asType());
      String propertyName = component.getSimpleName().toString();
      String fieldName = constantName(propertyName);
      if (usedFieldNames.contains(fieldName)) {
        fieldName += "_PROPERTY";
      }
      usedFieldNames.add(fieldName);
      String tableMethodName =
          RESERVED_TABLE_METHODS.contains(propertyName) ? propertyName + "Column" : propertyName;
      PropertyModel property =
          new PropertyModel(
              properties.size(),
              propertyName,
              fieldName,
              tableMethodName,
              descriptor.typeName(),
              descriptor.classLiteral(),
              descriptor.valueKind(),
              descriptor.primitive(),
              column,
              id != null,
              version != null,
              component);
      properties.add(property);
      components.add(new RecordComponentModel(propertyName, component, property, ""));
    }
    List<PropertyModel> primaryKey = properties.stream().filter(PropertyModel::id).toList();
    PropertyModel version =
        properties.stream().filter(PropertyModel::version).findFirst().orElse(null);
    return new EntityModel(
        type,
        packageName,
        packageName + ".skis",
        type.getSimpleName().toString(),
        type.getQualifiedName().toString(),
        new TableModel(catalog, schema, tableName),
        components,
        properties,
        primaryKey,
        version,
        entity.readOnly());
  }

  private void validateEntityShape(TypeElement type) throws EntityScanException {
    if (type.getNestingKind().isNested()) {
      throw failure("SKIS002", "the first processor slice only supports top-level entities", type);
    }
    if (!type.getModifiers().contains(Modifier.PUBLIC)) {
      throw failure("SKIS003", "the generated metadata package requires a public entity", type);
    }
    if (!type.getTypeParameters().isEmpty()) {
      throw failure("SKIS004", "the first processor slice does not support generic entities", type);
    }
    if (type.getKind() != ElementKind.RECORD) {
      throw failure("SKIS006", "the first processor slice only supports records", type);
    }
  }

  private TypeDescriptor describe(TypeMirror type) {
    if (type.getKind().isPrimitive()) {
      String boxed = types.boxedClass((PrimitiveType) type).getQualifiedName().toString();
      return new TypeDescriptor(boxed, boxed + ".class", kind(type), true);
    }
    if (type.getKind() == TypeKind.ARRAY) {
      ArrayType array = (ArrayType) type;
      JdbcValueKind valueKind =
          array.getComponentType().getKind() == TypeKind.BYTE
              ? JdbcValueKind.BYTES
              : JdbcValueKind.UNSUPPORTED;
      return new TypeDescriptor(type.toString(), type + ".class", valueKind, false);
    }
    if (type instanceof DeclaredType declaredType) {
      String rawType = types.erasure(declaredType).toString();
      if (!declaredType.getTypeArguments().isEmpty()) {
        return new TypeDescriptor(
            type.toString(), rawType + ".class", JdbcValueKind.UNSUPPORTED, false);
      }
      return new TypeDescriptor(rawType, rawType + ".class", kind(type), false);
    }
    return new TypeDescriptor(type.toString(), "", JdbcValueKind.UNSUPPORTED, false);
  }

  private JdbcValueKind kind(TypeMirror type) {
    if (type.getKind().isPrimitive()) {
      return JdbcValueKind.forPrimitive(type.getKind());
    }
    return JdbcValueKind.forDeclared(types.erasure(type).toString());
  }

  private static String defaultExpression(TypeMirror type) {
    return switch (type.getKind()) {
      case BOOLEAN -> "false";
      case CHAR -> "'\\0'";
      case BYTE, SHORT, INT -> "0";
      case LONG -> "0L";
      case FLOAT -> "0.0F";
      case DOUBLE -> "0.0D";
      default -> "null";
    };
  }

  private static boolean containsUnresolvedType(TypeMirror type) {
    return switch (type.getKind()) {
      case ERROR -> true;
      case ARRAY -> containsUnresolvedType(((ArrayType) type).getComponentType());
      case DECLARED ->
          ((DeclaredType) type)
              .getTypeArguments().stream().anyMatch(EntityModelScanner::containsUnresolvedType);
      default -> false;
    };
  }

  private static String snakeCase(String value) {
    StringBuilder result = new StringBuilder(value.length() + 8);
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      boolean uppercase = Character.isUpperCase(current);
      if (uppercase
          && index > 0
          && (Character.isLowerCase(value.charAt(index - 1))
              || Character.isDigit(value.charAt(index - 1))
              || (index + 1 < value.length() && Character.isLowerCase(value.charAt(index + 1))))) {
        result.append('_');
      }
      result.append(Character.toLowerCase(current));
    }
    return result.toString();
  }

  private static String constantName(String value) {
    return snakeCase(value).toUpperCase(Locale.ROOT);
  }

  private static boolean isExplicitlyNullable(RecordComponentElement component) {
    for (AnnotationMirror mirror : component.getAnnotationMirrors()) {
      TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
      if (!annotationType.getQualifiedName().contentEquals(Column.class.getCanonicalName())) {
        continue;
      }
      for (var entry : mirror.getElementValues().entrySet()) {
        if (entry.getKey().getSimpleName().contentEquals("nullable")
            && Boolean.TRUE.equals(entry.getValue().getValue())) {
          return true;
        }
      }
    }
    return false;
  }

  private static EntityScanException failure(
      String code, String message, javax.lang.model.element.Element element) {
    return new EntityScanException(code, message, element);
  }

  private record TypeDescriptor(
      String typeName, String classLiteral, JdbcValueKind valueKind, boolean primitive) {}
}
