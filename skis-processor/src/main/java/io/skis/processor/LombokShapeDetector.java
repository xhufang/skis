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

  static boolean mayAffectEntityShape(String diagnosticCode) {
    return switch (diagnosticCode) {
      case "SKIS031", "SKIS032", "SKIS033", "SKIS034", "SKIS035", "SKIS037", "SKIS039", "SKIS040" ->
          true;
      default -> false;
    };
  }

  private static boolean hasShapeAnnotation(Element element) {
    for (var mirror : element.getAnnotationMirrors()) {
      if (mirror.getAnnotationType().asElement() instanceof TypeElement annotationType
          && SHAPE_ANNOTATIONS.contains(annotationType.getQualifiedName().toString())) {
        return true;
      }
    }
    return false;
  }
}
