package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryTypeSafetyCompilationTest {

  @TempDir Path temporaryDirectory;

  @Test
  void keepsTheSelectedColumnEntityTypeThroughTheFromStage() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidQuery {
          static <E> void query(
              QueryOperations operations, QueryColumn<E, String> column, QueryTable<E> table) {
            operations.select(column).from(table);
          }
        }
        """;
    String invalid =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidQuery {
          static final class Pet {}
          static final class Owner {}
          static void query(
              QueryOperations operations,
              QueryColumn<Pet, String> column,
              QueryTable<Owner> table) {
            operations.select(column).from(table);
          }
        }
        """;

    assertTrue(compile("samples.ValidQuery", valid, temporaryDirectory.resolve("valid")));
    assertFalse(compile("samples.InvalidQuery", invalid, temporaryDirectory.resolve("invalid")));
  }

  private static boolean compile(String className, String source, Path output) throws IOException {
    Files.createDirectories(output);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      JavaCompiler.CompilationTask task =
          compiler.getTask(
              null,
              fileManager,
              diagnostics,
              List.of(
                  "-proc:none",
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-d",
                  output.toString()),
              null,
              List.of(new SourceFile(className, source)));
      boolean success = Boolean.TRUE.equals(task.call());
      if (!success && diagnostics.getDiagnostics().stream()
          .noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
        throw new AssertionError("compilation failed without an error diagnostic");
      }
      return success;
    }
  }

  private static final class SourceFile extends SimpleJavaFileObject {

    private final String source;

    private SourceFile(String className, String source) {
      super(
          URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
          Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
