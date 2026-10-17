package io.skis.mutation;

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

final class MutationTypeSafetyCompilationTest {

  @TempDir Path temporaryDirectory;

  @Test
  void entityMutationsRequireQueryTables() throws Exception {
    String valid =
        """
        package samples;

        import io.skis.mutation.MutationOperations;
        import io.skis.query.QueryTable;

        final class ValidEntityMutations {
          static final class Pet {}

          static int mutate(MutationOperations operations, QueryTable<Pet> pet, Pet value) {
            int affected = operations.insert(pet, value);
            affected += operations.updateById(pet, value);
            affected += operations.deleteById(pet, 1L);
            return affected;
          }
        }
        """;
    String invalidInsertTarget =
        invalidDerivedTargetSource(
            "InvalidDerivedInsert", "operations.insert(derived, value)");
    String invalidUpdateTarget =
        invalidDerivedTargetSource(
            "InvalidDerivedUpdate", "operations.updateById(derived, value)");
    String invalidDeleteTarget =
        invalidDerivedTargetSource(
            "InvalidDerivedDelete", "operations.deleteById(derived, 1L)");

    assertTrue(
        compile(
            "samples.ValidEntityMutations",
            valid,
            temporaryDirectory.resolve("valid-entity-mutations")));
    assertFalse(
        compile(
            "samples.InvalidDerivedInsert",
            invalidInsertTarget,
            temporaryDirectory.resolve("invalid-derived-insert")));
    assertFalse(
        compile(
            "samples.InvalidDerivedUpdate",
            invalidUpdateTarget,
            temporaryDirectory.resolve("invalid-derived-update")));
    assertFalse(
        compile(
            "samples.InvalidDerivedDelete",
            invalidDeleteTarget,
            temporaryDirectory.resolve("invalid-derived-delete")));
  }

  private static String invalidDerivedTargetSource(String simpleName, String invocation) {
    return """
        package samples;

        import io.skis.mutation.MutationOperations;
        import io.skis.query.DerivedRelation;

        final class %s {
          static final class Pet {}

          static int mutate(MutationOperations operations, DerivedRelation derived, Pet value) {
            return %s;
          }
        }
        """
        .formatted(simpleName, invocation);
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
      if (!success
          && diagnostics.getDiagnostics().stream()
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
