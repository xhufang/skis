package io.skis.processor;

import io.skis.annotations.SkisEntity;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/** Aggregates generated metadata class names into the deterministic SKIS entity index. */
@SupportedAnnotationTypes("io.skis.annotations.SkisEntity")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class SkisEntityIndexProcessor extends AbstractProcessor {

  private static final String INDEX_PATH = "META-INF/skis/entities.idx";

  private final Set<String> metadataTypes = new TreeSet<>();
  private final List<Element> originatingElements = new ArrayList<>();
  private boolean written;

  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    for (Element element : roundEnvironment.getElementsAnnotatedWith(SkisEntity.class)) {
      if (element instanceof TypeElement type) {
        String packageName =
            processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        metadataTypes.add(packageName + ".skis." + type.getSimpleName() + "Meta");
        originatingElements.add(type);
      }
    }
    if (roundEnvironment.processingOver() && !written && !metadataTypes.isEmpty()) {
      writeIndex();
    }
    return false;
  }

  private void writeIndex() {
    written = true;
    try {
      FileObject resource =
          processingEnv
              .getFiler()
              .createResource(
                  StandardLocation.CLASS_OUTPUT,
                  "",
                  INDEX_PATH,
                  originatingElements.toArray(Element[]::new));
      try (Writer writer = resource.openWriter()) {
        writer.write("# skis-generated-abi=");
        writer.write(Integer.toString(SourceText.GENERATED_ABI));
        writer.write('\n');
        for (String metadataType : metadataTypes) {
          writer.write(metadataType);
          writer.write('\n');
        }
      }
    } catch (IOException exception) {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR, "[SKIS098] cannot generate " + INDEX_PATH + ": " + exception);
    }
  }
}
