package io.skis.processor;

import io.skis.annotations.ProjectionConstructor;
import io.skis.annotations.ProjectionProperty;
import io.skis.annotations.SkisProjection;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
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

  private static final String SKIS_PROJECTION = SkisProjection.class.getCanonicalName();

  private final Elements elements;
  private final Types types;
  private final EntityModelScanner entityScanner;

  ProjectionModelScanner(Elements elements, Types types) {
    this.elements = elements;
    this.types = types;
    this.entityScanner = new EntityModelScanner(elements, types);
  }

  ProjectionModel scan(TypeElement type, boolean deferLombokSettlement)
      throws ProjectionScanException, ProjectionScanDeferredException {
    if (type.getAnnotation(SkisProjection.class) == null) {
      throw failure("SKIS201", "a projection type must be annotated with @SkisProjection", type);
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
    String generatedPackage = packageName + ".skis";
    EntityModel entity = scanEntity(type, deferLombokSettlement);
    List<ProjectionModel.ProjectionParameter> parameters =
        new ArrayList<>(constructor.getParameters().size());
    for (VariableElement parameter : constructor.getParameters()) {
      requireResolvedType(parameter);
      requireAccessibleType(parameter, generatedPackage);
      String parameterName = parameter.getSimpleName().toString();
      String propertyName = projectionPropertyName(parameter, parameterName);
      PropertyModel property =
          entity.properties().stream()
              .filter(candidate -> candidate.name().equals(propertyName))
              .findFirst()
              .orElseThrow(
                  () ->
                      failure(
                          "SKIS221",
                          "projection constructor parameter '"
                              + parameterName
                              + "' selects unknown property '"
                              + propertyName
                              + "' from entity '"
                              + entity.entityName()
                              + "'",
                          parameter));
      String parameterType = queryValueType(parameter.asType());
      if (!isSameQueryValueType(parameter.asType(), propertyType(property))) {
        throw failure(
            "SKIS222",
            "projection constructor parameter '"
                + parameterName
                + "' has type '"
                + parameterType
                + "' but entity property '"
                + propertyName
                + "' has type '"
                + property.typeName()
                + "'",
            parameter);
      }
      boolean primitive = parameter.asType().getKind().isPrimitive();
      if (primitive && property.column().nullable()) {
        throw failure(
            "SKIS223",
            "primitive projection constructor parameter '"
                + parameterName
                + "' cannot select nullable entity property '"
                + propertyName
                + "'",
            parameter);
      }
      parameters.add(
          new ProjectionModel.ProjectionParameter(parameterName, parameterType, property));
    }
    return new ProjectionModel(
        type,
        generatedPackage,
        type.getSimpleName().toString(),
        type.getQualifiedName().toString(),
        entity,
        parameters);
  }

  private EntityModel scanEntity(TypeElement projection, boolean deferLombokSettlement)
      throws ProjectionScanException, ProjectionScanDeferredException {
    TypeMirror entityType = projectionEntityType(projection);
    if (containsUnresolvedType(entityType)) {
      throw new ProjectionScanDeferredException(
          "projection entity type is still unresolved: '" + entityType + "'");
    }
    if (!(types.asElement(entityType) instanceof TypeElement entity)) {
      throw failure(
          "SKIS219", "@SkisProjection entity must name a declared entity type", projection);
    }
    if (deferLombokSettlement && LombokShapeDetector.isPresent(entity)) {
      throw new ProjectionScanDeferredException(
          "Lombok may still be changing projection entity '" + entity.getQualifiedName() + "'");
    }
    try {
      return entityScanner.scan(entity);
    } catch (EntityScanDeferredException deferred) {
      throw new ProjectionScanDeferredException(
          "projection entity '"
              + entity.getQualifiedName()
              + "' is not resolved: "
              + deferred.getMessage());
    } catch (EntityScanException failure) {
      if (LombokShapeDetector.isPresent(entity)
          && LombokShapeDetector.mayAffectEntityShape(failure.code())) {
        throw new ProjectionScanDeferredException(
            "Lombok-backed projection entity '"
                + entity.getQualifiedName()
                + "' has not reached a supported Simple Entity shape; last structural diagnostic was ["
                + failure.code()
                + "] "
                + failure.getMessage());
      }
      throw new ProjectionScanException(
          "SKIS220",
          "invalid projection entity '"
              + entity.getQualifiedName()
              + "': ["
              + failure.code()
              + "] "
              + failure.getMessage(),
          projection);
    }
  }

  private TypeMirror projectionEntityType(TypeElement projection) throws ProjectionScanException {
    for (AnnotationMirror annotation : projection.getAnnotationMirrors()) {
      if (!annotation.getAnnotationType().toString().equals(SKIS_PROJECTION)) {
        continue;
      }
      for (var entry : elements.getElementValuesWithDefaults(annotation).entrySet()) {
        if (entry.getKey().getSimpleName().contentEquals("entity")) {
          Object value = entry.getValue().getValue();
          if (value instanceof TypeMirror type) {
            return type;
          }
        }
      }
    }
    throw failure("SKIS219", "@SkisProjection must declare an entity type", projection);
  }

  private static String projectionPropertyName(VariableElement parameter, String parameterName)
      throws ProjectionScanException {
    ProjectionProperty mapping = parameter.getAnnotation(ProjectionProperty.class);
    if (mapping == null) {
      return parameterName;
    }
    String propertyName = mapping.value().trim();
    if (propertyName.isEmpty()) {
      throw failure(
          "SKIS224", "@ProjectionProperty must name a persistent entity property", parameter);
    }
    return propertyName;
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

  private String queryValueType(TypeMirror type) {
    TypeKind kind = type.getKind();
    if (kind.isPrimitive()) {
      return types.boxedClass((PrimitiveType) type).getQualifiedName().toString();
    }
    return type.toString();
  }

  private boolean isSameQueryValueType(TypeMirror projectionType, TypeMirror entityPropertyType) {
    return types.isSameType(boxedType(projectionType), boxedType(entityPropertyType));
  }

  private TypeMirror boxedType(TypeMirror type) {
    if (!type.getKind().isPrimitive()) {
      return type;
    }
    return types.boxedClass((PrimitiveType) type).asType();
  }

  private static TypeMirror propertyType(PropertyModel property) {
    Element element = property.element();
    return element instanceof ExecutableElement method ? method.getReturnType() : element.asType();
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
}
