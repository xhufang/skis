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
  void decouplesNullableAndNonNullSelectionsFromTheirFromRoot() throws Exception {
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
    String joinedTarget =
        """
        package samples;
        import io.skis.query.*;
        final class JoinedTargetQuery {
          static final class Pet {}
          static final class Owner {}
          static void query(
              QueryOperations operations,
              NonNullQueryColumn<Owner, String> ownerName,
              NullableQueryColumn<Owner, String> ownerNickname,
              QueryTable<Pet> pet,
              QueryTable<Owner> owner,
              QueryCondition on) {
            SelectQuery<Pet, String> query = operations.select(ownerName).from(pet);
            NullableSelectQuery<Pet, String> nullable =
                operations.select(ownerNickname).from(pet).leftJoin(owner).on(on);
            NullableSelectQuery<Pet, String> explicitNullable =
                operations.selectNullable(ownerName).from(pet).leftJoin(owner).on(on);
            NullableSelectQuery<Pet, Owner> nullableEntity =
                operations.selectNullable(owner).from(pet).leftJoin(owner).on(on);
            query.join(owner).on(on);
            nullable.where(ownerNickname.isNull());
            explicitNullable.fetchOne();
            nullableEntity.fetchOne();
          }
        }
        """;
    String nullableFromDecoupling =
        """
        package samples;
        import io.skis.query.*;
        final class NullableFromDecoupling {
          static final class Pet {}
          static final class Owner {}
          static void query(
              QueryOperations operations,
              NullableQueryColumn<Owner, String> ownerNickname,
              QueryTable<Pet> pet) {
            operations.select(ownerNickname).from(pet);
          }
        }
        """;

    assertTrue(compile("samples.ValidQuery", valid, temporaryDirectory.resolve("valid")));
    assertTrue(
        compile(
            "samples.JoinedTargetQuery",
            joinedTarget,
            temporaryDirectory.resolve("joined-target")));
    assertTrue(
        compile(
            "samples.NullableFromDecoupling",
            nullableFromDecoupling,
            temporaryDirectory.resolve("nullable-from-decoupling")));
  }

  @Test
  void keepsProjectionResultAndFromRootGenericsIndependent()
      throws Exception {
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
              ProjectionSelection<Summary> selection,
              QueryPredicate<Pet> predicate) {
            operations.select(selection).from(table).where(predicate);
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
              ProjectionSelection<Summary> selection,
              QueryPredicate<Owner> predicate) {
            operations.select(selection).from(table).where(predicate);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidProjectionQuery", valid, temporaryDirectory.resolve("valid-projection")));
    assertTrue(
        compile(
            "samples.InvalidProjectionQuery",
            invalid,
            temporaryDirectory.resolve("invalid-projection")));
  }

  @Test
  void generatedProjectionSignaturesRejectArityTypeAndNullnessMismatches() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidGeneratedProjectionCall {
          static final class Pet {}
          static final class Summary {}
          static ProjectionSelection<Summary> of(
              NonNullSelectable<Long> id, Selectable<String> name) { return null; }
          static void query(
              NonNullQueryColumn<Pet, Long> id, NullableQueryColumn<Pet, String> name) {
            ProjectionSelection<Summary> selection = of(id, name);
          }
        }
        """;
    String wrongOrder =
        """
        package samples;
        import io.skis.query.*;
        final class WrongProjectionOrder {
          static final class Pet {}
          static final class Summary {}
          static ProjectionSelection<Summary> of(
              NonNullSelectable<Long> id, Selectable<String> name) { return null; }
          static void query(
              NonNullQueryColumn<Pet, Long> id, NullableQueryColumn<Pet, String> name) {
            of(name, id);
          }
        }
        """;
    String wrongNullness =
        """
        package samples;
        import io.skis.query.*;
        final class WrongProjectionNullness {
          static final class Pet {}
          static final class Summary {}
          static ProjectionSelection<Summary> of(
              NonNullSelectable<Long> id, Selectable<String> name) { return null; }
          static void query(
              NullableQueryColumn<Pet, Long> id, NullableQueryColumn<Pet, String> name) {
            of(id, name);
          }
        }
        """;
    String wrongArity =
        """
        package samples;
        import io.skis.query.*;
        final class WrongProjectionArity {
          static final class Pet {}
          static final class Summary {}
          static ProjectionSelection<Summary> of(
              NonNullSelectable<Long> id, Selectable<String> name) { return null; }
          static void query(NonNullQueryColumn<Pet, Long> id) {
            of(id);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidGeneratedProjectionCall",
            valid,
            temporaryDirectory.resolve("valid-generated-projection")));
    assertFalse(
        compile(
            "samples.WrongProjectionOrder",
            wrongOrder,
            temporaryDirectory.resolve("wrong-projection-order")));
    assertFalse(
        compile(
            "samples.WrongProjectionNullness",
            wrongNullness,
            temporaryDirectory.resolve("wrong-projection-nullness")));
    assertFalse(
        compile(
            "samples.WrongProjectionArity",
            wrongArity,
            temporaryDirectory.resolve("wrong-projection-arity")));
  }

  @Test
  void keepsNarrowPredicatesAndAllowsWideConditionChains() throws Exception {
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
    String wide =
        """
        package samples;
        import io.skis.query.*;
        final class WideChain {
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
    assertTrue(
        compile("samples.WideChain", wide, temporaryDirectory.resolve("wide-chain")));
  }

  @Test
  void requiresOnBeforeAJoinCanReachTerminalOperations() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidJoin {
          static final class Pet {}
          static final class Owner {}
          static void query(
              SelectQuery<Pet, Pet> query,
              QueryTable<Owner> owner,
              QueryCondition condition) {
            query.join(owner).on(condition).fetchList();
          }
        }
        """;
    String invalid =
        """
        package samples;
        import io.skis.query.*;
        final class IncompleteJoin {
          static final class Pet {}
          static final class Owner {}
          static void query(SelectQuery<Pet, Pet> query, QueryTable<Owner> owner) {
            query.join(owner).fetchList();
          }
        }
        """;

    assertTrue(compile("samples.ValidJoin", valid, temporaryDirectory.resolve("valid-join")));
    assertFalse(
        compile(
            "samples.IncompleteJoin", invalid, temporaryDirectory.resolve("incomplete-join")));
  }

  @Test
  void checksColumnComparisonJavaTypesAtCompilationTime() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidColumnComparison {
          static final class Pet {}
          static final class Owner {}
          static QueryCondition condition(
              NonNullQueryColumn<Pet, Long> ownerId,
              NonNullQueryColumn<Owner, Long> id) {
            return ownerId.eq(id).and(ownerId.ge(id));
          }
        }
        """;
    String invalid =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidColumnComparison {
          static final class Pet {}
          static final class Owner {}
          static QueryCondition condition(
              NonNullQueryColumn<Pet, Long> ownerId,
              NonNullQueryColumn<Owner, String> id) {
            return ownerId.eq(id);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidColumnComparison",
            valid,
            temporaryDirectory.resolve("valid-column-comparison")));
    assertFalse(
        compile(
            "samples.InvalidColumnComparison",
            invalid,
            temporaryDirectory.resolve("invalid-column-comparison")));
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
            NullableSelectQuery<E, String> names = operations.select(nickname).from(table);
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
