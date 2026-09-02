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
              QueryOperations operations, NonNullQueryColumn<E, String> column, QueryTable<E> table) {
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
              NonNullQueryColumn<Pet, String> column,
              QueryTable<Owner> table) {
            operations.select(column).from(table);
          }
        }
        """;

    assertTrue(compile("samples.ValidQuery", valid, temporaryDirectory.resolve("valid")));
    assertFalse(compile("samples.InvalidQuery", invalid, temporaryDirectory.resolve("invalid")));
  }

  @Test
  void rejectsAProjectionPredicateFromAnotherEntityAtCompilationTime() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidProjectionQuery {
          static final class Pet {}
          static final class Summary {}
          static void query(
              QueryOperations operations,
              QueryTable<Pet> table,
              QueryPredicate<Pet> predicate) {
            operations.selectProjection(table, Summary.class).where(predicate);
          }
        }
        """;
    String invalid =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidProjectionQuery {
          static final class Pet {}
          static final class Owner {}
          static final class Summary {}
          static void query(
              QueryOperations operations,
              QueryTable<Pet> table,
              QueryPredicate<Owner> predicate) {
            operations.selectProjection(table, Summary.class).where(predicate);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidProjectionQuery", valid, temporaryDirectory.resolve("valid-projection")));
    assertFalse(
        compile(
            "samples.InvalidProjectionQuery",
            invalid,
            temporaryDirectory.resolve("invalid-projection")));
  }

  @Test
  void keepsEntityTypeAcrossQueryLevelAndOrChains() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidChain {
          static <E> void query(
              SelectQuery<E, E> query,
              QueryPredicate<E> first,
              QueryPredicate<E> second) {
            query.where(first).and(second).or(first);
          }
        }
        """;
    String invalid =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidChain {
          static final class Pet {}
          static final class Owner {}
          static void query(
              SelectQuery<Pet, Pet> query,
              QueryPredicate<Pet> pet,
              QueryPredicate<Owner> owner) {
            query.where(pet).and(owner);
          }
        }
        """;

    assertTrue(compile("samples.ValidChain", valid, temporaryDirectory.resolve("valid-chain")));
    assertFalse(
        compile("samples.InvalidChain", invalid, temporaryDirectory.resolve("invalid-chain")));
  }

  @Test
  void separatesNullableScalarRowPresenceFromNonNullOptionalQueries() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidNullableScalar {
          static <E> void query(
              QueryOperations operations,
              NonNullQueryColumn<E, Long> id,
              NullableQueryColumn<E, String> nickname,
              QueryTable<E> table) {
            SelectQuery<E, Long> ids = operations.select(id).from(table);
            NullableScalarQuery<E, String> names = operations.select(nickname).from(table);
            SingleRow<String> row = names.fetchOne();
            names.orderBy(nickname.asc().nullsLast(), id.asc());
          }
        }
        """;
    String invalid =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidNullableScalar {
          static <E> void query(
              QueryOperations operations,
              NullableQueryColumn<E, String> nickname,
              QueryTable<E> table) {
            SelectQuery<E, String> names = operations.select(nickname).from(table);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidNullableScalar",
            valid,
            temporaryDirectory.resolve("valid-nullable-scalar")));
    assertFalse(
        compile(
            "samples.InvalidNullableScalar",
            invalid,
            temporaryDirectory.resolve("invalid-nullable-scalar")));
  }

  @Test
  void exposesAConstructibleExplicitCountQueryForPageFallback() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidExplicitCount {
          static <E, R> Page<R> query(
              SelectQuery<E, R> content, SelectQuery<E, E> equivalentCountSource) {
            CountQuery count = equivalentCountSource.countQuery();
            return content.fetchPage(PageRequest.page(0, 20), count);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidExplicitCount",
            valid,
            temporaryDirectory.resolve("valid-explicit-count")));
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
