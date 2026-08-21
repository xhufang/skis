package io.skis.processor;

import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Transient;
import io.skis.annotations.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

final class EntityModelScanner {

  private static final Set<String> RESERVED_META_FIELDS =
      Set.of("TABLE", "ENTITY", "VERSION", "PRIMARY_KEY", "GENERATED_ABI");
  private static final Set<String> RESERVED_TABLE_METHODS = Set.of("as", "alias", "entity");

  private final Elements elements;
  private final Types types;
  private final TypeMirror runtimeExceptionType;
  private final TypeMirror errorType;
  private final TypeMirror sqlExceptionType;

  EntityModelScanner(Elements elements, Types types) {
    this.elements = elements;
    this.types = types;
    this.runtimeExceptionType =
        elements.getTypeElement(RuntimeException.class.getCanonicalName()).asType();
    this.errorType = elements.getTypeElement(Error.class.getCanonicalName()).asType();
    this.sqlExceptionType = elements.getTypeElement("java.sql.SQLException").asType();
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
    ScanResult result =
        type.getKind() == ElementKind.RECORD ? scanRecord(type) : scanBeanClass(type);
    List<PropertyModel> primaryKey =
        result.properties().stream().filter(PropertyModel::id).toList();
    PropertyModel version =
        result.properties().stream().filter(PropertyModel::version).findFirst().orElse(null);
    return new EntityModel(
        type,
        packageName + ".skis",
        type.getSimpleName().toString(),
        type.getQualifiedName().toString(),
        new TableModel(catalog, schema, tableName),
        result.instantiation(),
        result.properties(),
        primaryKey,
        version,
        entity.readOnly());
  }

  private ScanResult scanRecord(TypeElement type)
      throws EntityScanException, EntityScanDeferredException {
    List<PropertyModel> properties = new ArrayList<>(type.getRecordComponents().size());
    List<EntityInstantiationModel.ConstructorArgument> arguments =
        new ArrayList<>(type.getRecordComponents().size());
    Set<String> usedFieldNames = new HashSet<>(RESERVED_META_FIELDS);
    for (RecordComponentElement component : type.getRecordComponents()) {
      requireResolvedType(component.asType(), component.getSimpleName().toString());
      PropertyAnnotations annotations = annotations(component, null);
      validateAnnotationCombination(annotations, component);
      if (annotations.transientProperty()) {
        arguments.add(
            EntityInstantiationModel.ConstructorArgument.defaultValue(
                defaultExpression(component.asType())));
        continue;
      }
      if (!component.getAccessor().getModifiers().contains(Modifier.PUBLIC)) {
        throw failure(
            "SKIS025",
            "the generated row decoder cannot access record component '"
                + component.getSimpleName()
                + "'",
            component);
      }
      PropertyModel property =
          createProperty(
              properties.size(),
              component.getSimpleName().toString(),
              component.asType(),
              annotations,
              PropertyAccessModel.recordAccessor(
                  component.getAccessor().getSimpleName().toString()),
              component,
              usedFieldNames);
      properties.add(property);
      arguments.add(EntityInstantiationModel.ConstructorArgument.property(property));
    }
    return new ScanResult(
        EntityInstantiationModel.recordConstructor(arguments), List.copyOf(properties));
  }

  private ScanResult scanBeanClass(TypeElement type)
      throws EntityScanException, EntityScanDeferredException {
    validateBeanClassInheritance(type);
    validatePublicNoArgsConstructor(type);
    List<VariableElement> fields =
        ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
            .filter(EntityModelScanner::isPropertyField)
            .toList();
    List<ExecutableElement> methods = ElementFilter.methodsIn(type.getEnclosedElements());
    List<PropertyModel> properties = new ArrayList<>(fields.size());
    Set<String> fieldPropertyNames = new HashSet<>();
    Set<String> usedFieldNames = new HashSet<>(RESERVED_META_FIELDS);
    for (VariableElement field : fields) {
      String propertyName = field.getSimpleName().toString();
      fieldPropertyNames.add(propertyName);
      if (field.asType().getKind() == TypeKind.BOOLEAN && hasBooleanIsPrefix(propertyName)) {
        fieldPropertyNames.add(decapitalize(propertyName.substring(2)));
      }
      requireResolvedType(field.asType(), propertyName);
      ExecutableElement getter = findGetter(methods, propertyName, field.asType());
      PropertyAnnotations propertyAnnotations = annotations(field, getter);
      validateAnnotationCombination(propertyAnnotations, propertyAnnotations.source());
      if (propertyAnnotations.transientProperty()) {
        continue;
      }
      PropertyAccessModel access =
          resolveBeanAccess(field, getter, methods, propertyName, field.asType());
      properties.add(
          createProperty(
              properties.size(),
              propertyName,
              field.asType(),
              propertyAnnotations,
              access,
              propertyAnnotations.source(),
              usedFieldNames));
    }
    for (ExecutableElement getter : methods) {
      if (!hasSkisPropertyAnnotation(getter)) {
        continue;
      }
      String propertyName = getterPropertyName(getter);
      if (propertyName == null || fieldPropertyNames.contains(propertyName)) {
        continue;
      }
      requireResolvedType(getter.getReturnType(), propertyName);
      PropertyAnnotations propertyAnnotations = annotations(null, getter);
      validateAnnotationCombination(propertyAnnotations, getter);
      if (propertyAnnotations.transientProperty()) {
        continue;
      }
      PropertyAccessModel access =
          resolveBeanAccess(null, getter, methods, propertyName, getter.getReturnType());
      properties.add(
          createProperty(
              properties.size(),
              propertyName,
              getter.getReturnType(),
              propertyAnnotations,
              access,
              getter,
              usedFieldNames));
    }
    return new ScanResult(EntityInstantiationModel.bean(), List.copyOf(properties));
  }

  private PropertyModel createProperty(
      int ordinal,
      String propertyName,
      TypeMirror propertyType,
      PropertyAnnotations annotations,
      PropertyAccessModel access,
      Element source,
      Set<String> usedFieldNames) {
    Column annotation = annotations.column();
    String columnName = annotation == null ? "" : annotation.name();
    if (columnName.isEmpty()) {
      columnName = snakeCase(propertyName);
    }
    boolean nullable =
        !annotations.id()
            && !annotations.version()
            && (annotation == null || annotation.nullable());
    ColumnModel column =
        new ColumnModel(
            columnName,
            nullable,
            annotations.explicitlyNullable(),
            annotation == null || annotation.insertable(),
            annotation == null || annotation.updatable(),
            annotation == null ? 255 : annotation.length(),
            annotation == null ? 0 : annotation.precision(),
            annotation == null ? 0 : annotation.scale(),
            annotation == null ? "" : annotation.comment());
    TypeDescriptor descriptor = describe(propertyType);
    String fieldName = constantName(propertyName);
    if (usedFieldNames.contains(fieldName)) {
      fieldName += "_PROPERTY";
    }
    usedFieldNames.add(fieldName);
    String tableMethodName =
        RESERVED_TABLE_METHODS.contains(propertyName) ? propertyName + "Column" : propertyName;
    return new PropertyModel(
        ordinal,
        propertyName,
        fieldName,
        tableMethodName,
        descriptor.typeName(),
        descriptor.classLiteral(),
        descriptor.valueKind(),
        descriptor.primitive(),
        column,
        annotations.id(),
        annotations.version(),
        access,
        source);
  }

  private PropertyAccessModel resolveBeanAccess(
      VariableElement field,
      ExecutableElement getter,
      List<ExecutableElement> methods,
      String propertyName,
      TypeMirror propertyType)
      throws EntityScanException, EntityScanDeferredException {
    PropertyAccessModel.Access read;
    if (getter != null && getter.getModifiers().contains(Modifier.PUBLIC)) {
      validateGetterInvocation(getter, propertyName);
      read = PropertyAccessModel.Access.method(getter.getSimpleName().toString());
    } else if (field != null && field.getModifiers().contains(Modifier.PUBLIC)) {
      read = PropertyAccessModel.Access.field(field.getSimpleName().toString());
    } else {
      throw failure(
          "SKIS034",
          "property '"
              + propertyName
              + "' requires a public getter or public field accessible from the generated package",
          getter != null ? getter : field);
    }

    ExecutableElement setter = findSetter(methods, propertyName, propertyType);
    PropertyAccessModel.Access write;
    if (setter != null && setter.getModifiers().contains(Modifier.PUBLIC)) {
      write = PropertyAccessModel.Access.method(setter.getSimpleName().toString());
    } else if (field != null
        && field.getModifiers().contains(Modifier.PUBLIC)
        && !field.getModifiers().contains(Modifier.FINAL)) {
      write = PropertyAccessModel.Access.field(field.getSimpleName().toString());
    } else {
      throw failure(
          "SKIS035",
          "property '"
              + propertyName
              + "' requires a public setter or writable public field for row decoding",
          field != null ? field : getter);
    }
    return PropertyAccessModel.bean(read, write);
  }

  private ExecutableElement findGetter(
      List<ExecutableElement> methods, String propertyName, TypeMirror propertyType)
      throws EntityScanException, EntityScanDeferredException {
    Set<String> candidates = new LinkedHashSet<>();
    for (String suffix : accessorSuffixes(propertyName)) {
      if (propertyType.getKind() == TypeKind.BOOLEAN) {
        candidates.add("is" + suffix);
      }
      candidates.add("get" + suffix);
    }
    if (propertyType.getKind() == TypeKind.BOOLEAN && hasBooleanIsPrefix(propertyName)) {
      for (String suffix : accessorSuffixes(propertyName.substring(2))) {
        candidates.add("is" + suffix);
        candidates.add("get" + suffix);
      }
    }
    candidates.add(propertyName);
    ExecutableElement nonPublicCompatible = null;
    ExecutableElement incompatibleType = null;
    ExecutableElement incompatibleThrows = null;
    ExecutableElement unresolvedThrows = null;
    for (String candidate : candidates) {
      for (ExecutableElement method : methods) {
        if (!method.getSimpleName().contentEquals(candidate)
            || method.getModifiers().contains(Modifier.STATIC)
            || !method.getParameters().isEmpty()) {
          continue;
        }
        if (!types.isSameType(method.getReturnType(), propertyType)) {
          if (incompatibleType == null) {
            incompatibleType = method;
          }
          continue;
        }
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
          if (nonPublicCompatible == null) {
            nonPublicCompatible = method;
          }
          continue;
        }
        InvocationCompatibility compatibility = invocationCompatibility(method);
        if (compatibility == InvocationCompatibility.COMPATIBLE) {
          return method;
        }
        if (compatibility == InvocationCompatibility.UNRESOLVED) {
          if (unresolvedThrows == null) {
            unresolvedThrows = method;
          }
        } else if (incompatibleThrows == null) {
          incompatibleThrows = method;
        }
      }
    }
    if (unresolvedThrows != null) {
      throw unresolvedThrownType(unresolvedThrows, "getter", propertyName);
    }
    if (incompatibleThrows != null) {
      throw incompatibleAccessorThrows(incompatibleThrows, "getter", propertyName);
    }
    if (nonPublicCompatible != null) {
      return nonPublicCompatible;
    }
    if (incompatibleType != null) {
      throw failure(
          "SKIS037",
          "getter '"
              + incompatibleType.getSimpleName()
              + "' does not return the declared type of property '"
              + propertyName
              + "'",
          incompatibleType);
    }
    return null;
  }

  private ExecutableElement findSetter(
      List<ExecutableElement> methods, String propertyName, TypeMirror propertyType)
      throws EntityScanException, EntityScanDeferredException {
    Set<String> candidates = new LinkedHashSet<>();
    for (String suffix : accessorSuffixes(propertyName)) {
      candidates.add("set" + suffix);
    }
    if (propertyType.getKind() == TypeKind.BOOLEAN && hasBooleanIsPrefix(propertyName)) {
      for (String suffix : accessorSuffixes(propertyName.substring(2))) {
        candidates.add("set" + suffix);
      }
    }
    candidates.add(propertyName);
    ExecutableElement nonPublicCompatible = null;
    ExecutableElement incompatibleType = null;
    ExecutableElement incompatibleThrows = null;
    ExecutableElement unresolvedThrows = null;
    for (String candidate : candidates) {
      for (ExecutableElement method : methods) {
        if (!method.getSimpleName().contentEquals(candidate)
            || method.getModifiers().contains(Modifier.STATIC)
            || method.getParameters().size() != 1) {
          continue;
        }
        if (!types.isSameType(method.getParameters().getFirst().asType(), propertyType)) {
          if (incompatibleType == null) {
            incompatibleType = method;
          }
          continue;
        }
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
          if (nonPublicCompatible == null) {
            nonPublicCompatible = method;
          }
          continue;
        }
        InvocationCompatibility compatibility = invocationCompatibility(method);
        if (compatibility == InvocationCompatibility.COMPATIBLE) {
          return method;
        }
        if (compatibility == InvocationCompatibility.UNRESOLVED) {
          if (unresolvedThrows == null) {
            unresolvedThrows = method;
          }
        } else if (incompatibleThrows == null) {
          incompatibleThrows = method;
        }
      }
    }
    if (unresolvedThrows != null) {
      throw unresolvedThrownType(unresolvedThrows, "setter", propertyName);
    }
    if (incompatibleThrows != null) {
      throw incompatibleAccessorThrows(incompatibleThrows, "setter", propertyName);
    }
    if (nonPublicCompatible != null) {
      return nonPublicCompatible;
    }
    if (incompatibleType != null) {
      throw failure(
          "SKIS037",
          "setter '"
              + incompatibleType.getSimpleName()
              + "' does not accept the declared type of property '"
              + propertyName
              + "'",
          incompatibleType);
    }
    return null;
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
    if (type.getKind() != ElementKind.RECORD && type.getKind() != ElementKind.CLASS) {
      throw failure("SKIS031", "a simple entity must be a record or concrete class", type);
    }
    if (type.getKind() == ElementKind.CLASS && type.getModifiers().contains(Modifier.ABSTRACT)) {
      throw failure("SKIS031", "a simple class entity must not be abstract", type);
    }
  }

  private void validateBeanClassInheritance(TypeElement type) throws EntityScanException {
    TypeElement objectType = elements.getTypeElement(Object.class.getCanonicalName());
    if (objectType != null
        && !types.isSameType(types.erasure(type.getSuperclass()), objectType.asType())) {
      throw failure(
          "SKIS032",
          "class entity inheritance is not supported until entity inheritance semantics are defined",
          type);
    }
  }

  private void validatePublicNoArgsConstructor(TypeElement type)
      throws EntityScanException, EntityScanDeferredException {
    List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
    if (constructors.isEmpty()) {
      return;
    }
    ExecutableElement constructor =
        constructors.stream()
            .filter(candidate -> candidate.getParameters().isEmpty())
            .filter(candidate -> candidate.getModifiers().contains(Modifier.PUBLIC))
            .findFirst()
            .orElse(null);
    if (constructor == null) {
      throw failure(
          "SKIS033",
          "a class entity requires a public no-args constructor for generated row decoding",
          type);
    }
    InvocationCompatibility compatibility = invocationCompatibility(constructor);
    if (compatibility == InvocationCompatibility.UNRESOLVED) {
      throw unresolvedThrownType(constructor, "constructor", type.getSimpleName().toString());
    }
    if (compatibility == InvocationCompatibility.INCOMPATIBLE_CHECKED) {
      throw failure(
          "SKIS039",
          "the public no-args constructor declares checked exception(s) "
              + incompatibleCheckedExceptions(constructor)
              + " that the generated row decoder cannot propagate",
          constructor);
    }
  }

  private PropertyAnnotations annotations(Element field, Element getter)
      throws EntityScanException {
    Column fieldColumn = field == null ? null : field.getAnnotation(Column.class);
    Column getterColumn = getter == null ? null : getter.getAnnotation(Column.class);
    if (fieldColumn != null && getterColumn != null && !sameColumn(fieldColumn, getterColumn)) {
      throw failure(
          "SKIS036",
          "field and getter declare conflicting @Column mappings for the same property",
          getter);
    }
    Element source = hasSkisPropertyAnnotation(getter) ? getter : field;
    return new PropertyAnnotations(
        getterColumn == null ? fieldColumn : getterColumn,
        hasAnnotation(field, Id.class) || hasAnnotation(getter, Id.class),
        hasAnnotation(field, Version.class) || hasAnnotation(getter, Version.class),
        hasAnnotation(field, Transient.class) || hasAnnotation(getter, Transient.class),
        isExplicitlyNullable(field) || isExplicitlyNullable(getter),
        source == null ? getter : source);
  }

  private static void validateAnnotationCombination(
      PropertyAnnotations annotations, Element element) throws EntityScanException {
    if (annotations.transientProperty() && (annotations.id() || annotations.version())) {
      throw failure("SKIS007", "@Transient cannot be combined with @Id or @Version", element);
    }
  }

  private InvocationCompatibility invocationCompatibility(ExecutableElement executable) {
    boolean unresolved = false;
    for (TypeMirror thrownType : executable.getThrownTypes()) {
      if (containsUnresolvedType(thrownType)) {
        unresolved = true;
      } else if (isIncompatibleCheckedException(thrownType)) {
        return InvocationCompatibility.INCOMPATIBLE_CHECKED;
      }
    }
    return unresolved ? InvocationCompatibility.UNRESOLVED : InvocationCompatibility.COMPATIBLE;
  }

  private boolean isIncompatibleCheckedException(TypeMirror thrownType) {
    TypeMirror erasedThrownType = types.erasure(thrownType);
    return !types.isSubtype(erasedThrownType, types.erasure(sqlExceptionType))
        && !types.isSubtype(erasedThrownType, types.erasure(runtimeExceptionType))
        && !types.isSubtype(erasedThrownType, types.erasure(errorType));
  }

  private List<String> incompatibleCheckedExceptions(ExecutableElement executable) {
    return executable.getThrownTypes().stream()
        .filter(thrownType -> !containsUnresolvedType(thrownType))
        .filter(this::isIncompatibleCheckedException)
        .map(TypeMirror::toString)
        .toList();
  }

  private EntityScanException incompatibleAccessorThrows(
      ExecutableElement accessor, String accessorKind, String propertyName) {
    return failure(
        "SKIS040",
        accessorKind
            + " '"
            + accessor.getSimpleName()
            + "' for property '"
            + propertyName
            + "' declares checked exception(s) "
            + incompatibleCheckedExceptions(accessor)
            + " that generated code cannot propagate",
        accessor);
  }

  private void validateGetterInvocation(ExecutableElement getter, String propertyName)
      throws EntityScanException, EntityScanDeferredException {
    InvocationCompatibility compatibility = invocationCompatibility(getter);
    if (compatibility == InvocationCompatibility.UNRESOLVED) {
      throw unresolvedThrownType(getter, "getter", propertyName);
    }
    if (compatibility == InvocationCompatibility.INCOMPATIBLE_CHECKED) {
      throw incompatibleAccessorThrows(getter, "getter", propertyName);
    }
  }

  private static EntityScanDeferredException unresolvedThrownType(
      ExecutableElement executable, String executableKind, String propertyName) {
    return new EntityScanDeferredException(
        executableKind
            + " '"
            + executable.getSimpleName()
            + "' for '"
            + propertyName
            + "' still has an unresolved thrown type");
  }

  private void requireResolvedType(TypeMirror type, String propertyName)
      throws EntityScanDeferredException {
    if (containsUnresolvedType(type)) {
      throw new EntityScanDeferredException(
          "property '" + propertyName + "' still has unresolved type '" + type + "'");
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

  private static String getterPropertyName(ExecutableElement method) {
    if (method.getModifiers().contains(Modifier.STATIC)
        || !method.getParameters().isEmpty()
        || method.getReturnType().getKind() == TypeKind.VOID) {
      return null;
    }
    String methodName = method.getSimpleName().toString();
    if (methodName.startsWith("get") && methodName.length() > 3) {
      return decapitalize(methodName.substring(3));
    }
    if (methodName.startsWith("is")
        && methodName.length() > 2
        && method.getReturnType().getKind() == TypeKind.BOOLEAN) {
      return decapitalize(methodName.substring(2));
    }
    return methodName;
  }

  private static List<String> accessorSuffixes(String propertyName) {
    Set<String> suffixes = new LinkedHashSet<>();
    suffixes.add(Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1));
    if (propertyName.length() > 1 && Character.isUpperCase(propertyName.charAt(1))) {
      suffixes.add(propertyName);
    }
    return List.copyOf(suffixes);
  }

  private static boolean hasBooleanIsPrefix(String propertyName) {
    return propertyName.length() > 2
        && propertyName.startsWith("is")
        && Character.isUpperCase(propertyName.charAt(2));
  }

  private static String decapitalize(String value) {
    if (value.length() > 1
        && Character.isUpperCase(value.charAt(0))
        && Character.isUpperCase(value.charAt(1))) {
      return value;
    }
    return Character.toLowerCase(value.charAt(0)) + value.substring(1);
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

  private static boolean isExplicitlyNullable(Element element) {
    if (element == null) {
      return false;
    }
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
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

  private static boolean sameColumn(Column left, Column right) {
    return left.name().equals(right.name())
        && left.nullable() == right.nullable()
        && left.insertable() == right.insertable()
        && left.updatable() == right.updatable()
        && left.length() == right.length()
        && left.precision() == right.precision()
        && left.scale() == right.scale()
        && left.comment().equals(right.comment());
  }

  private static boolean hasSkisPropertyAnnotation(Element element) {
    return hasAnnotation(element, Column.class)
        || hasAnnotation(element, Id.class)
        || hasAnnotation(element, Version.class)
        || hasAnnotation(element, Transient.class);
  }

  private static boolean isPropertyField(VariableElement field) {
    if (field.getModifiers().contains(Modifier.STATIC)) {
      return false;
    }
    String fieldName = field.getSimpleName().toString();
    return !fieldName.startsWith("$") || hasSkisPropertyAnnotation(field);
  }

  private static boolean hasAnnotation(
      Element element, Class<? extends java.lang.annotation.Annotation> annotationType) {
    return element != null && element.getAnnotation(annotationType) != null;
  }

  private static EntityScanException failure(String code, String message, Element element) {
    return new EntityScanException(code, message, element);
  }

  private record ScanResult(
      EntityInstantiationModel instantiation, List<PropertyModel> properties) {}

  private record PropertyAnnotations(
      Column column,
      boolean id,
      boolean version,
      boolean transientProperty,
      boolean explicitlyNullable,
      Element source) {}

  private enum InvocationCompatibility {
    COMPATIBLE,
    INCOMPATIBLE_CHECKED,
    UNRESOLVED
  }

  private record TypeDescriptor(
      String typeName, String classLiteral, JdbcValueKind valueKind, boolean primitive) {}
}
