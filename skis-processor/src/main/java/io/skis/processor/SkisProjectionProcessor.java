package io.skis.processor;

import io.skis.annotations.SkisProjection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/** Generates one strongly typed, reflection-free mapper for each user projection type. */
@SupportedAnnotationTypes("io.skis.annotations.SkisProjection")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class SkisProjectionProcessor extends AbstractProcessor {

  private ProjectionModelScanner scanner;
  private ProjectionGenerator generator;
  private final Set<String> settledProjections = new HashSet<>();
  private final Set<String> pendingProjections = new TreeSet<>();
  private final Map<String, String> deferredProblems = new HashMap<>();

  @Override
  public synchronized void init(ProcessingEnvironment environment) {
    super.init(environment);
    scanner = new ProjectionModelScanner(environment.getElementUtils(), environment.getTypeUtils());
    generator = new ProjectionGenerator();
  }

  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    roundEnvironment.getElementsAnnotatedWith(SkisProjection.class).stream()
        .filter(TypeElement.class::isInstance)
        .map(TypeElement.class::cast)
        .map(type -> type.getQualifiedName().toString())
        .filter(projectionName -> !settledProjections.contains(projectionName))
        .forEach(pendingProjections::add);

    if (roundEnvironment.processingOver()) {
      reportUnresolvedProjections();
      return false;
    }
    for (String projectionName : new ArrayList<>(pendingProjections)) {
      TypeElement type = processingEnv.getElementUtils().getTypeElement(projectionName);
      if (type == null) {
        deferredProblems.put(projectionName, "the projection type is not available in this round");
        continue;
      }
      ProcessingResult result = processProjection(type);
      if (result != ProcessingResult.DEFERRED) {
        pendingProjections.remove(projectionName);
        deferredProblems.remove(projectionName);
        settledProjections.add(projectionName);
      }
    }
    return false;
  }

  private ProcessingResult processProjection(TypeElement type) {
    String projectionName = type.getQualifiedName().toString();
    try {
      generator.generate(scanner.scan(type), processingEnv.getFiler());
      return ProcessingResult.GENERATED;
    } catch (ProjectionScanDeferredException deferred) {
      deferredProblems.put(projectionName, deferred.getMessage());
      return ProcessingResult.DEFERRED;
    } catch (ProjectionScanException failure) {
      error(failure.code(), failure.getMessage(), failure.element());
      return ProcessingResult.FAILED;
    } catch (IOException failure) {
      error(
          "SKIS299",
          "cannot generate projection mapper for '" + type.getQualifiedName() + "': " + failure,
          type);
      return ProcessingResult.FAILED;
    }
  }

  private void reportUnresolvedProjections() {
    for (String projectionName : pendingProjections) {
      String reason =
          deferredProblems.getOrDefault(
              projectionName, "the projection constructor parameter type remained unresolved");
      String message =
          "cannot generate projection mapper for '"
              + projectionName
              + "' because "
              + reason
              + "; ensure the referenced type is generated before processing ends";
      TypeElement type = processingEnv.getElementUtils().getTypeElement(projectionName);
      if (type == null) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[SKIS217] " + message);
      } else {
        error("SKIS217", message, type);
      }
    }
    settledProjections.addAll(pendingProjections);
    pendingProjections.clear();
    deferredProblems.clear();
  }

  private void error(String code, String message, Element element) {
    processingEnv
        .getMessager()
        .printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message, element);
  }

  private enum ProcessingResult {
    GENERATED,
    FAILED,
    DEFERRED
  }
}
