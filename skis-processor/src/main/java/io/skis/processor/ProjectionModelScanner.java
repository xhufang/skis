package io.skis.processor;

import io.skis.annotations.ProjectionConstructor;
import io.skis.annotations.PropertyFrom;
import io.skis.annotations.ReusableProjection;
import io.skis.annotations.SpecialProjection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

final class ProjectionModelScanner {

  private static final String NULLABLE = "org.jspecify.annotations.Nullable";
  private static final String PROPERTY_META = "io.skis.metadata.PropertyMeta";
  private static final String SELECTABLE = "io.skis.query.Selectable";
  private static final String NON_NULL_SELECTABLE = "io.skis.query.NonNullSelectable";
  private static final Set<String> RESERVED_TABLE_METHODS = Set.of("as", "alias", "entity");

  private final Elements elements;
  private final Types types;

  ProjectionModelScanner(Elements elements, Types types) {
    this.elements = elements;
    this.types = types;
  }

  ProjectionModel scan(TypeElement type, boolean deferLombokSettlement)
      throws ProjectionScanException, ProjectionScanDeferredException {
    boolean reusable = type.getAnnotation(ReusableProjection.class) != null;
    boolean special = type.getAnnotation(SpecialProjection.class) != null;
    if (!reusable && !special) {
      throw failure(
          "SKIS201",
          "a projection type must be annotated with @ReusableProjection or @SpecialProjection",
          type);
    }
    if (reusable && special) {
      throw failure(
          "SKIS225", "@ReusableProjection and @SpecialProjection are mutually exclusive", type);
    }
    if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
      throw failure("SKIS202", "a projection must be a class or record", type);
    }
    if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
      throw failure("SKIS203", "a projection must be a top-level type", type);
    }
    if (!type.getModifiers().contains(Modifier.PUBLIC)) {
      throw failure("SKIS204", "a projection type must be public", type);
    }
    if (type.getKind() == ElementKind.CLASS && type.getModifiers().contains(Modifier.ABSTRACT)) {
      throw failure("SKIS214", "a projection class must be concrete", type);
    }
    if (!type.getTypeParameters().isEmpty()) {
      throw failure("SKIS205", "generic projection types are not supported", type);
    }
    String packageName = elements.getPackageOf(type).getQualifiedName().toString();
    if (packageName.isEmpty()) {
      throw failure("SKIS206", "a projection must declare a package", type);
    }
    if (deferLombokSettlement && LombokShapeDetector.isPresent(type)) {
      throw new ProjectionScanDeferredException(
          "Lombok may still be changing the projection constructor shape");
    }
    ExecutableElement constructor = selectConstructor(type);
    validateConstructor(constructor);
    String generatedPackage = packageName + ".skis";
    List<ProjectionModel.ProjectionParameter> parameters =
        new ArrayList<>(constructor.getParameters().size());
    for (VariableElement parameter : constructor.getParameters()) {
      requireResolvedType(parameter);
      requireAccessibleType(parameter, generatedPackage);
      TypeMirror parameterType = parameter.asType();
      TypeDescriptor descriptor = describe(parameterType);
      parameters.add(
          new ProjectionModel.ProjectionParameter(
              parameter.getSimpleName().toString(),
              descriptor.typeName(),
              descriptor.erasedTypeName(),
              descriptor.classLiteral(),
              descriptor.primitive(),
              descriptor.constructorTypeName(),
              acceptsNull(parameter)));
    }
    ProjectionModel.ProjectionKind kind =
        reusable ? ProjectionModel.ProjectionKind.REUSABLE : ProjectionModel.ProjectionKind.SPECIAL;
    List<ProjectionModel.ProjectionSource> sources =
        kind == ProjectionModel.ProjectionKind.SPECIAL
            ? scanSources(type, constructor)
            : rejectSourcesOnReusable(type);
    return new ProjectionModel(
        type,
        generatedPackage,
        type.getSimpleName().toString(),
        type.getQualifiedName().toString(),
        elements.getBinaryName(type).toString(),
        constructorDescriptor(constructor),
        kind,
        parameters,
        sources);
  }

  private List<ProjectionModel.ProjectionSource> rejectSourcesOnReusable(TypeElement type)
      throws ProjectionScanException {
    for (RecordComponentElement component : type.getRecordComponents()) {
      if (component.getAnnotation(PropertyFrom.class) != null) {
        throw failure("SKIS226", "@PropertyFrom is only valid on a @SpecialProjection", component);
      }
    }
    for (Element element : type.getEnclosedElements()) {
      if (element.getAnnotation(PropertyFrom.class) != null) {
        throw failure("SKIS226", "@PropertyFrom is only valid on a @SpecialProjection", element);
      }
      if (element instanceof ExecutableElement executable) {
        for (VariableElement parameter : executable.getParameters()) {
          if (parameter.getAnnotation(PropertyFrom.class) != null) {
            throw failure(
                "SKIS226", "@PropertyFrom is only valid on a @SpecialProjection", parameter);
          }
        }
      }
    }
    return List.of();
  }

  private List<ProjectionModel.ProjectionSource> scanSources(
      TypeElement type, ExecutableElement constructor)
      throws ProjectionScanException, ProjectionScanDeferredException {
    Map<String, VariableElement> annotatedFields = new LinkedHashMap<>();
    if (type.getKind() == ElementKind.CLASS) {
      for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
        if (field.getAnnotation(PropertyFrom.class) != null) {
          annotatedFields.put(field.getSimpleName().toString(), field);
        }
      }
    }
    List<? extends RecordComponentElement> recordComponents =
        type.getKind() == ElementKind.RECORD ? type.getRecordComponents() : List.of();
    List<ProjectionModel.ProjectionSource> result =
        new ArrayList<>(constructor.getParameters().size());
    for (int index = 0; index < constructor.getParameters().size(); index++) {
      VariableElement parameter = constructor.getParameters().get(index);
      PropertyFrom direct = parameter.getAnnotation(PropertyFrom.class);
      Element declaration = parameter;
      if (type.getKind() == ElementKind.RECORD) {
        RecordComponentElement recordComponent = recordComponents.get(index);
        PropertyFrom component = recordComponent.getAnnotation(PropertyFrom.class);
        if (direct != null
            && component != null
            && !sameSourceDeclaration(direct, parameter, component, recordComponent)) {
          throw failure(
              "SKIS232",
              "special projection parameter '"
                  + parameter.getSimpleName()
                  + "' declares conflicting @PropertyFrom values on the record component "
                  + "and canonical constructor parameter",
              parameter);
        }
        if (direct == null) {
          direct = component;
          declaration = recordComponent;
        }
      } else {
        VariableElement field = annotatedFields.remove(parameter.getSimpleName().toString());
        if (direct != null && field != null) {
          throw failure(
              "SKIS232",
              "special projection parameter '"
                  + parameter.getSimpleName()
                  + "' declares @PropertyFrom on both the parameter and matching field",
              parameter);
        }
        if (direct == null && field != null) {
          validateSourceField(parameter, field);
          direct = field.getAnnotation(PropertyFrom.class);
          declaration = field;
        }
      }
      if (direct == null) {
        throw failure(
            "SKIS226",
            "special projection parameter '"
                + parameter.getSimpleName()
                + "' must declare @PropertyFrom",
            parameter);
      }
      result.add(resolveSource(parameter, direct, declaration));
    }
    if (!annotatedFields.isEmpty()) {
      VariableElement field = annotatedFields.values().iterator().next();
      throw failure(
          "SKIS232",
          "@PropertyFrom field '"
              + field.getSimpleName()
              + "' does not correspond to a selected constructor parameter",
          field);
    }
    rejectUnusedParameterSources(type, constructor);
    return List.copyOf(result);
  }

  private boolean sameSourceDeclaration(
      PropertyFrom parameterSource,
      Element parameter,
      PropertyFrom componentSource,
      Element component)
      throws ProjectionScanDeferredException {
    return annotationValue(parameterSource, parameter)
            .equals(annotationValue(componentSource, component))
        && annotationAlias(parameterSource, parameter)
            .equals(annotationAlias(componentSource, component));
  }

  private void validateSourceField(VariableElement parameter, VariableElement field)
      throws ProjectionScanException {
    if (field.getModifiers().contains(Modifier.STATIC)) {
      throw failure(
          "SKIS232",
          "@PropertyFrom field '" + field.getSimpleName() + "' must not be static",
          field);
    }
    if (!types.isSameType(parameter.asType(), field.asType())) {
      throw failure(
          "SKIS232",
          "@PropertyFrom field '"
              + field.getSimpleName()
              + "' has type '"
              + field.asType()
              + "' but its constructor parameter has type '"
              + parameter.asType()
              + "'",
          field);
    }
    if (acceptsNull(parameter) != acceptsNull(field)) {
      throw failure(
          "SKIS232",
          "@PropertyFrom field '"
              + field.getSimpleName()
              + "' and its constructor parameter must declare the same nullability",
          field);
    }
  }

  private void rejectUnusedParameterSources(TypeElement type, ExecutableElement constructor)
      throws ProjectionScanException {
    for (ExecutableElement executable : ElementFilter.methodsIn(type.getEnclosedElements())) {
      rejectParameterSources(executable);
    }
    for (ExecutableElement candidate : ElementFilter.constructorsIn(type.getEnclosedElements())) {
      if (!candidate.equals(constructor)) {
        rejectParameterSources(candidate);
      }
    }
  }

  private void rejectParameterSources(ExecutableElement executable) throws ProjectionScanException {
    for (VariableElement parameter : executable.getParameters()) {
      if (parameter.getAnnotation(PropertyFrom.class) != null) {
        throw failure(
            "SKIS232",
            "@PropertyFrom parameter '"
                + parameter.getSimpleName()
                + "' does not belong to the selected projection constructor",
            parameter);
      }
    }
  }

  private ProjectionModel.ProjectionSource resolveSource(
      VariableElement parameter, PropertyFrom annotation, Element declaration)
      throws ProjectionScanException, ProjectionScanDeferredException {
    String encodedSource = annotationValue(annotation, declaration);
    ParsedPropertyIdentity identity = parsePropertyIdentity(encodedSource, declaration);
    String alias = annotationAlias(annotation, declaration);
    if (!alias.isEmpty() && !isPortableIdentifier(alias)) {
      throw failure(
          "SKIS230",
          "@PropertyFrom alias '" + alias + "' must match [A-Za-z_][A-Za-z0-9_]*",
          declaration);
    }
    TypeElement entityType = elements.getTypeElement(identity.entityTypeName());
    if (entityType == null) {
      throw new ProjectionScanDeferredException(
          "@PropertyFrom entity type '" + identity.entityTypeName() + "' is not available yet");
    }
    String entityPackage = elements.getPackageOf(entityType).getQualifiedName().toString();
    String generatedPrefix = entityPackage + ".skis." + entityType.getSimpleName();
    String metaTypeName = generatedPrefix + "Meta";
    String tableTypeName = generatedPrefix + "Table";
    TypeElement metaType = elements.getTypeElement(metaTypeName);
    TypeElement tableType = elements.getTypeElement(tableTypeName);
    if (metaType == null || tableType == null) {
      throw new ProjectionScanDeferredException(
          "generated entity metadata for '" + identity.entityTypeName() + "' is not available yet");
    }
    VariableElement sourceConstant =
        ElementFilter.fieldsIn(tableType.getEnclosedElements()).stream()
            .filter(field -> encodedSource.equals(field.getConstantValue()))
            .findFirst()
            .orElseThrow(
                () ->
                    failure(
                        "SKIS231",
                        "@PropertyFrom references unknown persistent property '"
                            + identity.propertyName()
                            + "' on entity '"
                            + identity.entityTypeName()
                            + "'",
                        declaration));
    String metaFieldName = sourceConstant.getSimpleName().toString();
    VariableElement metaField =
        ElementFilter.fieldsIn(metaType.getEnclosedElements()).stream()
            .filter(field -> field.getSimpleName().contentEquals(metaFieldName))
            .findFirst()
            .orElseThrow(
                () ->
                    failure(
                        "SKIS231",
                        "@PropertyFrom references unknown persistent property '"
                            + identity.propertyName()
                            + "' on entity '"
                            + identity.entityTypeName()
                            + "'",
                        declaration));
    validatePropertyType(parameter, entityType, metaField, declaration);
    validatePropertyNullability(parameter, tableType, identity.propertyName(), declaration);
    return new ProjectionModel.ProjectionSource(metaTypeName, metaFieldName, alias);
  }

  private static boolean isPortableIdentifier(String value) {
    if (value.isEmpty() || !isIdentifierInitial(value.charAt(0))) {
      return false;
    }
    for (int index = 1; index < value.length(); index++) {
      if (!isIdentifierSubsequent(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isIdentifierInitial(char character) {
    return character == '_'
        || character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z';
  }

  private static boolean isIdentifierSubsequent(char character) {
    return isIdentifierInitial(character) || character >= '0' && character <= '9';
  }

  private static String annotationValue(PropertyFrom annotation, Element declaration)
      throws ProjectionScanDeferredException {
    try {
      String value = annotation.value();
      if (value.contains("<error>")) {
        throw new ProjectionScanDeferredException(
            "@PropertyFrom value on '" + declaration.getSimpleName() + "' is not resolved yet");
      }
      return value;
    } catch (RuntimeException unresolved) {
      throw new ProjectionScanDeferredException(
          "@PropertyFrom value on '" + declaration.getSimpleName() + "' is not resolved yet");
    }
  }

  private static String annotationAlias(PropertyFrom annotation, Element declaration)
      throws ProjectionScanDeferredException {
    try {
      return annotation.alias();
    } catch (RuntimeException unresolved) {
      throw new ProjectionScanDeferredException(
          "@PropertyFrom alias on '" + declaration.getSimpleName() + "' is not resolved yet");
    }
  }

  private ParsedPropertyIdentity parsePropertyIdentity(String value, Element declaration)
      throws ProjectionScanException {
    int separator = value.indexOf('#');
    if (separator <= 0 || separator != value.lastIndexOf('#') || separator + 1 == value.length()) {
      throw failure(
          "SKIS227",
          "@PropertyFrom value must use the complete '<entity-type>#<property-name>' format",
          declaration);
    }
    return new ParsedPropertyIdentity(
        value.substring(0, separator), value.substring(separator + 1));
  }

  private void validatePropertyType(
      VariableElement parameter,
      TypeElement entityType,
      VariableElement metaField,
      Element declaration)
      throws ProjectionScanException, ProjectionScanDeferredException {
    if (!(metaField.asType() instanceof DeclaredType declared)
        || !types.erasure(declared).toString().equals(PROPERTY_META)
        || declared.getTypeArguments().size() != 2) {
      throw failure(
          "SKIS231", "generated property metadata has an unsupported declaration", declaration);
    }
    TypeMirror sourceEntityType = declared.getTypeArguments().get(0);
    TypeMirror propertyType = declared.getTypeArguments().get(1);
    if (containsUnresolvedType(sourceEntityType) || containsUnresolvedType(propertyType)) {
      throw new ProjectionScanDeferredException("generated property metadata is not resolved yet");
    }
    if (!types.isSameType(types.erasure(sourceEntityType), types.erasure(entityType.asType()))) {
      throw failure(
          "SKIS227",
          "@PropertyFrom metadata does not match its encoded entity identity",
          declaration);
    }
    TypeMirror parameterType = parameter.asType();
    if (parameterType.getKind().isPrimitive()) {
      parameterType = types.boxedClass((PrimitiveType) parameterType).asType();
    }
    if (!types.isSameType(parameterType, propertyType)) {
      throw failure(
          "SKIS228",
          "special projection parameter '"
              + parameter.getSimpleName()
              + "' has type '"
              + parameter.asType()
              + "' but its property source has type '"
              + propertyType
              + "'",
          declaration);
    }
  }

  private void validatePropertyNullability(
      VariableElement parameter, TypeElement tableType, String propertyName, Element declaration)
      throws ProjectionScanException, ProjectionScanDeferredException {
    String methodName =
        RESERVED_TABLE_METHODS.contains(propertyName) ? propertyName + "Column" : propertyName;
    ExecutableElement method =
        ElementFilter.methodsIn(tableType.getEnclosedElements()).stream()
            .filter(candidate -> candidate.getSimpleName().contentEquals(methodName))
            .filter(candidate -> candidate.getParameters().isEmpty())
            .findFirst()
            .orElseThrow(
                () ->
                    failure(
                        "SKIS231",
                        "generated table has no accessor for persistent property '"
                            + propertyName
                            + "'",
                        declaration));
    if (containsUnresolvedType(method.getReturnType())) {
      throw new ProjectionScanDeferredException(
          "generated table property type is not resolved yet");
    }
    String returnType = types.erasure(method.getReturnType()).toString();
    if (!returnType.equals(SELECTABLE) && !returnType.equals(NON_NULL_SELECTABLE)) {
      throw failure(
          "SKIS231",
          "generated table property accessor has an unsupported return type",
          declaration);
    }
    if (returnType.equals(SELECTABLE) && !acceptsNull(parameter)) {
      throw failure(
          "SKIS229",
          "special projection parameter '"
              + parameter.getSimpleName()
              + "' must accept null because its property source is nullable",
          declaration);
    }
  }

  private void validateConstructor(ExecutableElement constructor) throws ProjectionScanException {
    if (!constructor.getModifiers().contains(Modifier.PUBLIC)) {
      throw failure("SKIS207", "the selected projection constructor must be public", constructor);
    }
    if (!constructor.getTypeParameters().isEmpty()) {
      throw failure("SKIS216", "generic projection constructors are not supported", constructor);
    }
    if (constructor.getParameters().isEmpty()) {
      throw failure(
          "SKIS208",
          "the selected projection constructor must declare at least one parameter",
          constructor);
    }
    if (constructor.isVarArgs()) {
      throw failure("SKIS209", "a projection constructor must not be variable arity", constructor);
    }
    if (!constructor.getThrownTypes().isEmpty()) {
      throw failure(
          "SKIS210", "a projection constructor must not declare thrown types", constructor);
    }
  }

  private ExecutableElement selectConstructor(TypeElement type)
      throws ProjectionScanException, ProjectionScanDeferredException {
    List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
    List<ExecutableElement> marked =
        constructors.stream()
            .filter(constructor -> constructor.getAnnotation(ProjectionConstructor.class) != null)
            .toList();
    if (marked.size() > 1) {
      throw failure("SKIS211", "a projection may declare only one @ProjectionConstructor", type);
    }
    if (type.getKind() == ElementKind.RECORD) {
      ExecutableElement canonical = canonicalRecordConstructor(type, constructors);
      if (marked.size() == 1 && marked.getFirst() != canonical) {
        throw failure(
            "SKIS215", "a record projection must use its canonical constructor", marked.getFirst());
      }
      return canonical;
    }
    if (marked.size() == 1) {
      return marked.getFirst();
    }
    List<ExecutableElement> publicConstructors =
        constructors.stream()
            .filter(constructor -> constructor.getModifiers().contains(Modifier.PUBLIC))
            .toList();
    if (publicConstructors.size() != 1) {
      throw failure(
          "SKIS212",
          "a projection class must expose exactly one public constructor or mark one with "
              + "@ProjectionConstructor",
          type);
    }
    return publicConstructors.getFirst();
  }

  private ExecutableElement canonicalRecordConstructor(
      TypeElement type, List<ExecutableElement> constructors)
      throws ProjectionScanException, ProjectionScanDeferredException {
    List<RecordComponentElement> components =
        ElementFilter.recordComponentsIn(type.getEnclosedElements());
    for (RecordComponentElement component : components) {
      if (containsUnresolvedType(component.asType())) {
        throw new ProjectionScanDeferredException(
            "record component '"
                + component.getSimpleName()
                + "' still has unresolved type '"
                + component.asType()
                + "'");
      }
    }
    for (ExecutableElement constructor : constructors) {
      if (constructor.getParameters().size() != components.size()) {
        continue;
      }
      boolean canonical = true;
      for (int index = 0; index < components.size(); index++) {
        if (!types.isSameType(
            constructor.getParameters().get(index).asType(), components.get(index).asType())) {
          canonical = false;
          break;
        }
      }
      if (canonical) {
        return constructor;
      }
    }
    throw failure("SKIS213", "cannot resolve the record canonical constructor", type);
  }

  private TypeDescriptor describe(TypeMirror type) {
    if (type.getKind().isPrimitive()) {
      String boxed = types.boxedClass((PrimitiveType) type).getQualifiedName().toString();
      return new TypeDescriptor(boxed, boxed, boxed + ".class", true, type.toString());
    }
    String erasedTypeName = erasedTypeName(type);
    String sourceTypeName = sourceTypeName(type);
    return new TypeDescriptor(
        sourceTypeName, erasedTypeName, erasedTypeName + ".class", false, sourceTypeName);
  }

  private String sourceTypeName(TypeMirror type) {
    return switch (type.getKind()) {
      case ARRAY -> sourceTypeName(((ArrayType) type).getComponentType()) + "[]";
      case DECLARED -> sourceDeclaredTypeName((DeclaredType) type);
      case WILDCARD -> sourceWildcardTypeName((WildcardType) type);
      default -> type.toString();
    };
  }

  private String sourceDeclaredTypeName(DeclaredType type) {
    TypeElement element = (TypeElement) type.asElement();
    TypeMirror enclosing = type.getEnclosingType();
    String rawName =
        enclosing.getKind() == TypeKind.NONE
            ? element.getQualifiedName().toString()
            : sourceTypeName(enclosing) + "." + element.getSimpleName();
    if (type.getTypeArguments().isEmpty()) {
      return rawName;
    }
    return rawName
        + "<"
        + type.getTypeArguments().stream()
            .map(this::sourceTypeName)
            .collect(java.util.stream.Collectors.joining(", "))
        + ">";
  }

  private String sourceWildcardTypeName(WildcardType type) {
    if (type.getExtendsBound() != null) {
      return "? extends " + sourceTypeName(type.getExtendsBound());
    }
    if (type.getSuperBound() != null) {
      return "? super " + sourceTypeName(type.getSuperBound());
    }
    return "?";
  }

  private String erasedTypeName(TypeMirror type) {
    if (type.getKind() == TypeKind.ARRAY) {
      return erasedTypeName(((ArrayType) type).getComponentType()) + "[]";
    }
    if (type.getKind().isPrimitive()) {
      return type.toString();
    }
    DeclaredType erased = (DeclaredType) types.erasure(type);
    return ((TypeElement) erased.asElement()).getQualifiedName().toString();
  }

  private String constructorDescriptor(ExecutableElement constructor) {
    StringBuilder descriptor = new StringBuilder("(");
    for (VariableElement parameter : constructor.getParameters()) {
      descriptor.append(jvmDescriptor(parameter.asType()));
    }
    return descriptor.append(")V").toString();
  }

  private String jvmDescriptor(TypeMirror type) {
    return switch (type.getKind()) {
      case BOOLEAN -> "Z";
      case BYTE -> "B";
      case SHORT -> "S";
      case INT -> "I";
      case LONG -> "J";
      case CHAR -> "C";
      case FLOAT -> "F";
      case DOUBLE -> "D";
      case ARRAY -> "[" + jvmDescriptor(((ArrayType) type).getComponentType());
      case DECLARED -> declaredDescriptor((DeclaredType) types.erasure(type));
      default ->
          throw new IllegalArgumentException("unsupported constructor parameter type " + type);
    };
  }

  private String declaredDescriptor(DeclaredType type) {
    TypeElement element = (TypeElement) type.asElement();
    return "L" + elements.getBinaryName(element).toString().replace('.', '/') + ";";
  }

  private static boolean acceptsNull(VariableElement parameter) {
    if (parameter.asType().getKind().isPrimitive()) {
      return false;
    }
    return parameter.getAnnotationMirrors().stream()
            .anyMatch(annotation -> annotation.getAnnotationType().toString().equals(NULLABLE))
        || parameter.asType().getAnnotationMirrors().stream()
            .anyMatch(annotation -> annotation.getAnnotationType().toString().equals(NULLABLE));
  }

  private void requireResolvedType(VariableElement parameter)
      throws ProjectionScanDeferredException {
    if (containsUnresolvedType(parameter.asType())) {
      throw new ProjectionScanDeferredException(
          "constructor parameter '"
              + parameter.getSimpleName()
              + "' still has unresolved type '"
              + parameter.asType()
              + "'");
    }
  }

  private void requireAccessibleType(VariableElement parameter, String generatedPackage)
      throws ProjectionScanException {
    if (!isAccessibleFrom(parameter.asType(), generatedPackage)) {
      throw failure(
          "SKIS218",
          "projection constructor parameter '"
              + parameter.getSimpleName()
              + "' has type '"
              + parameter.asType()
              + "' that is not accessible from generated package '"
              + generatedPackage
              + "'",
          parameter);
    }
  }

  private boolean isAccessibleFrom(TypeMirror type, String generatedPackage) {
    if (type.getAnnotationMirrors().stream()
        .map(annotation -> annotation.getAnnotationType().asElement())
        .filter(TypeElement.class::isInstance)
        .map(TypeElement.class::cast)
        .anyMatch(annotationType -> isTypeElementInaccessible(annotationType, generatedPackage))) {
      return false;
    }
    return switch (type.getKind()) {
      case ARRAY -> isAccessibleFrom(((ArrayType) type).getComponentType(), generatedPackage);
      case DECLARED -> isDeclaredTypeAccessible((DeclaredType) type, generatedPackage);
      case WILDCARD -> isWildcardAccessible((WildcardType) type, generatedPackage);
      case TYPEVAR -> isTypeVariableAccessible((TypeVariable) type, generatedPackage);
      case INTERSECTION ->
          ((IntersectionType) type)
              .getBounds().stream().allMatch(bound -> isAccessibleFrom(bound, generatedPackage));
      default ->
          type.getKind().isPrimitive()
              || type.getKind() == TypeKind.NULL
              || type.getKind() == TypeKind.NONE;
    };
  }

  private boolean isDeclaredTypeAccessible(DeclaredType type, String generatedPackage) {
    if (!(type.asElement() instanceof TypeElement element)
        || isTypeElementInaccessible(element, generatedPackage)) {
      return false;
    }
    TypeMirror enclosingType = type.getEnclosingType();
    return (enclosingType.getKind() == TypeKind.NONE
            || isAccessibleFrom(enclosingType, generatedPackage))
        && type.getTypeArguments().stream()
            .allMatch(argument -> isAccessibleFrom(argument, generatedPackage));
  }

  private boolean isTypeElementInaccessible(TypeElement type, String generatedPackage) {
    boolean samePackage =
        elements.getPackageOf(type).getQualifiedName().contentEquals(generatedPackage);
    Element current = type;
    while (current instanceof TypeElement enclosingType) {
      if (samePackage) {
        if (enclosingType.getModifiers().contains(Modifier.PRIVATE)) {
          return true;
        }
      } else if (!enclosingType.getModifiers().contains(Modifier.PUBLIC)) {
        return true;
      }
      current = enclosingType.getEnclosingElement();
    }
    return false;
  }

  private boolean isWildcardAccessible(WildcardType type, String generatedPackage) {
    TypeMirror extendsBound = type.getExtendsBound();
    TypeMirror superBound = type.getSuperBound();
    return (extendsBound == null || isAccessibleFrom(extendsBound, generatedPackage))
        && (superBound == null || isAccessibleFrom(superBound, generatedPackage));
  }

  private boolean isTypeVariableAccessible(TypeVariable type, String generatedPackage) {
    return isAccessibleFrom(type.getUpperBound(), generatedPackage)
        && isAccessibleFrom(type.getLowerBound(), generatedPackage);
  }

  private static boolean containsUnresolvedType(TypeMirror type) {
    return switch (type.getKind()) {
      case ERROR -> true;
      case ARRAY -> containsUnresolvedType(((ArrayType) type).getComponentType());
      case DECLARED -> containsUnresolvedDeclaredType((DeclaredType) type);
      case WILDCARD -> {
        WildcardType wildcard = (WildcardType) type;
        yield (wildcard.getExtendsBound() != null
                && containsUnresolvedType(wildcard.getExtendsBound()))
            || (wildcard.getSuperBound() != null
                && containsUnresolvedType(wildcard.getSuperBound()));
      }
      case TYPEVAR -> {
        TypeVariable variable = (TypeVariable) type;
        yield containsUnresolvedType(variable.getUpperBound())
            || containsUnresolvedType(variable.getLowerBound());
      }
      case INTERSECTION ->
          ((IntersectionType) type)
              .getBounds().stream().anyMatch(ProjectionModelScanner::containsUnresolvedType);
      default -> false;
    };
  }

  private static boolean containsUnresolvedDeclaredType(DeclaredType type) {
    return (type.getEnclosingType().getKind() != TypeKind.NONE
            && containsUnresolvedType(type.getEnclosingType()))
        || type.getTypeArguments().stream()
            .anyMatch(ProjectionModelScanner::containsUnresolvedType);
  }

  private static ProjectionScanException failure(String code, String message, Element element) {
    return new ProjectionScanException(code, message, element);
  }

  private record TypeDescriptor(
      String typeName,
      String erasedTypeName,
      String classLiteral,
      boolean primitive,
      String constructorTypeName) {}

  private record ParsedPropertyIdentity(String entityTypeName, String propertyName) {}
}
