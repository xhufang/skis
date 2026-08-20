package io.skis.processor;

import java.io.IOException;
import java.io.Writer;
import javax.annotation.processing.Filer;
import javax.tools.JavaFileObject;

interface EntitySourceGenerator {

  String suffix();

  String render(EntityModel model);

  default void generate(EntityModel model, Filer filer) throws IOException {
    JavaFileObject source =
        filer.createSourceFile(model.generatedQualifiedName(suffix()), model.type());
    try (Writer writer = source.openWriter()) {
      writer.write(render(model));
    }
  }
}
