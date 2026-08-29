package io.skis.processor;

import io.skis.annotations.SkisEntity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/** Generates the isolated, per-entity SKIS compile-time model. */
@SupportedAnnotationTypes("io.skis.annotations.SkisEntity")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class SkisEntityProcessor extends AbstractProcessor {

  private EntityModelScanner scanner;
  private EntityModelValidator validator;
  private Messager messager;
  private Filer filer;
  private List<EntitySourceGenerator> generators;
  private final Set<String> settledEntities = new HashSet<>();
  private final Set<String> lombokSettlementDeferred = new HashSet<>();
  private final Set<String> pendingEntities = new TreeSet<>();
  private final Map<String, DeferredProblem> deferredProblems = new HashMap<>();

  @Override
  public synchronized void init(ProcessingEnvironment environment) {
    super.init(environment);
    scanner = new EntityModelScanner(environment.getElementUtils(), environment.getTypeUtils());
    validator = new EntityModelValidator();
    messager = environment.getMessager();
    filer = environment.getFiler();
    generators =
        List.of(
            new MetaGenerator(),
            new TableGenerator(),
            new RowDecoderGenerator(),
            new BinderGenerator(),
            new RuntimeModelGenerator());
  }

  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    roundEnvironment.getElementsAnnotatedWith(SkisEntity.class).stream()
        .filter(TypeElement.class::isInstance)
        .map(TypeElement.class::cast)
        .map(type -> type.getQualifiedName().toString())
        .filter(entityName -> !settledEntities.contains(entityName))
        .forEach(pendingEntities::add);

    if (roundEnvironment.processingOver()) {
      reportUnresolvedEntities();
      return false;
    }
    for (String entityName : new ArrayList<>(pendingEntities)) {
      TypeElement type = processingEnv.getElementUtils().getTypeElement(entityName);
      if (type == null) {
        deferredProblems.put(
            entityName,
            new DeferredProblem("SKIS097", "the entity type is not available in this round"));
        continue;
      }
      ProcessingResult result = processEntity(type);
      if (result != ProcessingResult.DEFERRED) {
        pendingEntities.remove(entityName);
        deferredProblems.remove(entityName);
        settledEntities.add(entityName);
      }
    }
    // Leave the annotation visible to the separate aggregating index processor.
    return false;
  }

  private ProcessingResult processEntity(TypeElement type) {
    String entityName = type.getQualifiedName().toString();
    if (LombokShapeDetector.isPresent(type) && lombokSettlementDeferred.add(entityName)) {
      deferredProblems.put(
          entityName,
          new DeferredProblem(
              "SKIS038",
              "Lombok may still be changing the entity structure; waiting for its next annotation-processing round"));
      return ProcessingResult.DEFERRED;
    }
    try {
      EntityModel model = scanner.scan(type);
      List<ProcessingProblem> problems = validator.validate(model);
      if (!problems.isEmpty()) {
        problems.forEach(problem -> error(problem.code(), problem.message(), problem.element()));
        return ProcessingResult.FAILED;
      }
      for (EntitySourceGenerator generator : generators) {
        generator.generate(model, filer);
      }
      return ProcessingResult.GENERATED;
    } catch (EntityScanDeferredException deferred) {
      deferredProblems.put(entityName, new DeferredProblem("SKIS097", deferred.getMessage()));
      return ProcessingResult.DEFERRED;
    } catch (EntityScanException failure) {
      if (LombokShapeDetector.isPresent(type)
          && LombokShapeDetector.mayAffectEntityShape(failure.code())) {
        deferredProblems.put(
            entityName,
            new DeferredProblem(
                "SKIS038",
                "the Lombok-backed entity has not reached a supported Simple Entity shape; last structural diagnostic was ["
                    + failure.code()
                    + "] "
                    + failure.getMessage()));
        return ProcessingResult.DEFERRED;
      }
      error(failure.code(), failure.getMessage(), failure.element());
      return ProcessingResult.FAILED;
    } catch (IOException exception) {
      error("SKIS099", "cannot generate sources for '" + entityName + "': " + exception, type);
      return ProcessingResult.FAILED;
    }
  }

  private void reportUnresolvedEntities() {
    for (String entityName : pendingEntities) {
      DeferredProblem problem =
          deferredProblems.getOrDefault(
              entityName, new DeferredProblem("SKIS097", "the entity type remained unresolved"));
      String resolution =
          "SKIS038".equals(problem.code())
              ? "; ensure Lombok is enabled as an annotation processor and can request its follow-up round"
              : "; ensure the referenced type is generated before processing ends";
      String message =
          "cannot generate sources for '"
              + entityName
              + "' because "
              + problem.reason()
              + resolution;
      TypeElement type = processingEnv.getElementUtils().getTypeElement(entityName);
      if (type == null) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, DiagnosticGuidance.format(problem.code(), message));
      } else {
        error(problem.code(), message, type);
      }
    }
    settledEntities.addAll(pendingEntities);
    pendingEntities.clear();
    deferredProblems.clear();
  }

  private void error(String code, String message, Element element) {
    messager.printMessage(Diagnostic.Kind.ERROR, DiagnosticGuidance.format(code, message), element);
  }

  private enum ProcessingResult {
    GENERATED,
    FAILED,
    DEFERRED
  }

  private record DeferredProblem(String code, String reason) {}
}
