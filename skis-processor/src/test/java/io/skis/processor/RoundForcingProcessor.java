package io.skis.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/** Test processor that simulates a source transformer requesting one additional APT round. */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class RoundForcingProcessor extends AbstractProcessor {

  private boolean generated;

  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    if (generated || roundEnvironment.processingOver()) {
      return false;
    }
    generated = true;
    try (Writer writer =
        processingEnv.getFiler().createSourceFile("samples.RoundMarker").openWriter()) {
      writer.write("package samples;\n\nfinal class RoundMarker {}\n");
    } catch (IOException exception) {
      throw new IllegalStateException("cannot force the additional test round", exception);
    }
    return false;
  }
}
