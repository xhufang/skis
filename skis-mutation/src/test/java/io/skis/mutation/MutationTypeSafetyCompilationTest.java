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
  void stagedWritesHaveExactTypesAndRejectOldOrIllegalEntrypoints() throws Exception {
    String prefix = """
        package samples;
        import io.skis.mutation.*;
        import io.skis.query.*;
        import java.util.*;
        final class Contract {
          static void calls(MutationOperations op, QueryTable<String> table, String entity,
              ProjectionSelection<Integer> reusable, ProjectionType<Integer> special,
              NonNullSelectable<String> column) {
        """;
    String valid = """
        InsertInto<String> target = op.insertInto(table);
        InsertWrite<String> single = target.value(entity);
        int count = single.write();
        Written<String> full = single.returning().write();
        Written<Integer> one = single.returning(reusable).write();
        Written<Integer> two = single.returning(special).write();
        Written<List<String>> many = target.values(List.of(entity)).returning().write();
        Written<List<Integer>> projected = target.values(List.of(entity)).returning(special).write();
        UpdateWrite<String> update = op.update(table).set(column, entity);
        Written<List<Integer>> changed = update.where(column.eq(entity)).returning(reusable).write();
        Written<List<String>> deleted = op.deleteFrom(table).allowAllRows().returning().write();
        """;
    assertTrue(compile("samples.Contract", prefix + valid + "}}", temporaryDirectory.resolve("stages")));
    int index = 0;
    for (String illegal : List.of(
        "op.insert(table, entity);", "op.insertResult(table, entity);",
        "op.insertReturning(table, entity);", "op.batchInsert(table, List.of(entity));",
        "InsertResult<String> old;", "op.insertInto(table).write();",
        "op.insertInto(table).returning();", "op.insertInto(table).value(entity).values(List.of(entity));",
        "op.insertInto(table).value(entity).value(entity);",
        "op.insertInto(table).value(entity).returning().returning();",
        "op.insertInto(table).value(entity).returning(column);",
        "op.insertInto(table).value(entity).returning(String.class);",
        "op.insertInto(table).value(entity).returning((io.skis.mapping.spi.RowDecoder<String>) null);",
        "Written<String> bad = op.insertInto(table).value(entity).write();",
        "int bad = op.insertInto(table).value(entity).returning().write();",
        "Written<String> bad = op.insertInto(table).values(List.of(entity)).returning().write();",
        "op.update(table).write();", "op.update(table).set(column, 1);")) {
      assertFalse(compile("samples.Contract", prefix + illegal + "}}",
          temporaryDirectory.resolve("illegal-" + index++)), illegal);
    }
  }

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
            int affected = operations.insertInto(pet).value(value).write();
            affected += operations.updateById(pet, value);
            affected += operations.deleteById(pet, 1L);
            return affected;
          }
        }
        """;
    String invalidInsertTarget =
        invalidDerivedTargetSource("InvalidDerivedInsert", "operations.insertInto(derived).value(value).write()");
    String invalidUpdateTarget =
        invalidDerivedTargetSource("InvalidDerivedUpdate", "operations.updateById(derived, value)");
    String invalidDeleteTarget =
        invalidDerivedTargetSource("InvalidDerivedDelete", "operations.deleteById(derived, 1L)");

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
