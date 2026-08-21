package io.skis.processor;

import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** Detects optional Lombok transformations without linking SKIS to Lombok APIs. */
final class LombokShapeDetector {

  private static final Set<String> SHAPE_ANNOTATIONS =
      Set.of(
          "lombok.Data",
          "lombok.Getter",
          "lombok.Setter",
          "lombok.Value",
          "lombok.Builder",
          "lombok.NoArgsConstructor",
          "lombok.RequiredArgsConstructor",
          "lombok.AllArgsConstructor",
          "lombok.experimental.Delegate",
          "lombok.experimental.FieldDefaults",
          "lombok.experimental.SuperBuilder",
          "lombok.experimental.UtilityClass");

  private LombokShapeDetector() {}

  static boolean isPresent(TypeElement type) {
    if (hasShapeAnnotation(type)) {
      return true;
    }
    return type.getEnclosedElements().stream().anyMatch(LombokShapeDetector::hasShapeAnnotation);
  }

  private static boolean hasShapeAnnotation(Element element) {
    return element.getAnnotationMirrors().stream()
        .map(mirror -> mirror.getAnnotationType().asElement())
        .filter(TypeElement.class::isInstance)
        .map(TypeElement.class::cast)
        .map(annotationType -> annotationType.getQualifiedName().toString())
        .anyMatch(SHAPE_ANNOTATIONS::contains);
  }
}
