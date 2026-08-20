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
  private final Set<String> pendingEntities = new TreeSet<>();
  private final Map<String, String> deferredReasons = new HashMap<>();

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
            new BinderGenerator());
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
        deferredReasons.put(entityName, "the entity type is not available in this round");
        continue;
      }
      ProcessingResult result = processEntity(type);
      if (result != ProcessingResult.DEFERRED) {
        pendingEntities.remove(entityName);
        deferredReasons.remove(entityName);
        settledEntities.add(entityName);
      }
    }
    // Leave the annotation visible to the separate aggregating index processor.
    return false;
  }

  private ProcessingResult processEntity(TypeElement type) {
    String entityName = type.getQualifiedName().toString();
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
      deferredReasons.put(entityName, deferred.getMessage());
      return ProcessingResult.DEFERRED;
    } catch (EntityScanException failure) {
      error(failure.code(), failure.getMessage(), failure.element());
      return ProcessingResult.FAILED;
    } catch (IOException exception) {
      error(
          "SKIS099",
          "cannot generate sources for '" + entityName + "': " + exception,
          type);
      return ProcessingResult.FAILED;
    }
  }

  private void reportUnresolvedEntities() {
    for (String entityName : pendingEntities) {
      String reason =
          deferredReasons.getOrDefault(entityName, "the entity type remained unresolved");
      String message =
          "cannot generate sources for '"
              + entityName
              + "' because "
              + reason
              + "; ensure the referenced type is generated before processing ends";
      TypeElement type = processingEnv.getElementUtils().getTypeElement(entityName);
      if (type == null) {
        messager.printMessage(Diagnostic.Kind.ERROR, "[SKIS097] " + message);
      } else {
        error("SKIS097", message, type);
      }
    }
    settledEntities.addAll(pendingEntities);
    pendingEntities.clear();
    deferredReasons.clear();
  }

  private void error(String code, String message, Element element) {
    messager.printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message, element);
  }

  private enum ProcessingResult {
    GENERATED,
    FAILED,
    DEFERRED
  }
}
