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

/** Test processor that makes a previously unresolved property type available in a later round. */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class DeferredTypeGeneratorProcessor extends AbstractProcessor {

  private boolean generated;

  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    if (generated || roundEnvironment.processingOver()) {
      return false;
    }
    generated = true;
    try (Writer writer =
        processingEnv.getFiler().createSourceFile("samples.GeneratedMoney").openWriter()) {
      writer.write("package samples;\n\npublic record GeneratedMoney(long cents) {}\n");
    } catch (IOException exception) {
      throw new IllegalStateException("cannot generate the deferred test type", exception);
    }
    return false;
  }
}
