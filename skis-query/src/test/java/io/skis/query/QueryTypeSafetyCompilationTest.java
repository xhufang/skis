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
  void selectableContractsCompileAcrossConcreteInterfaceGenericAndMethodReferenceForms()
      throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        import java.util.function.Function;
        final class ValidQuery {
          static <V> QueryCondition equal(Selectable<V> left, Selectable<V> right) {
            return left.eq(right);
          }
          static <V> SortSpecification order(Selectable<V> selectable) {
            return selectable.asc();
          }
          static <V> NullableSelectFromStep<V> select(
              QueryOperations operations, Selectable<V> selectable) {
            return operations.select(selectable);
          }
          static <V> SelectFromStep<V> selectNonNull(
              QueryOperations operations, NonNullSelectable<V> selectable) {
            return operations.select(selectable);
          }
          static <E> void query(
              QueryOperations operations,
              NonNullQueryColumn<E, String> concrete,
              NullableQueryColumn<E, String> nullableConcrete,
              QueryTable<E> table) {
            NonNullSelectable<String> nonNull = concrete;
            Selectable<String> selectable = concrete;
            Selectable<String> nullable = nullableConcrete;
            SelectFromStep<String> concreteStep = operations.select(concrete);
            SelectFromStep<String> interfaceStep = operations.select(nonNull);
            NullableSelectFromStep<String> generalStep = operations.select(selectable);
            NullableSelectFromStep<String> nullableStep = select(operations, nullable);
            SelectFromStep<String> helperStep = selectNonNull(operations, nonNull);
            Function<NonNullSelectable<String>, SelectFromStep<String>> selector =
                operations::select;
            Function<String, QueryCondition> equality = concrete::eq;
            Function<Selectable<String>, QueryCondition> expressionEquality = concrete::eq;
            concreteStep.from(table);
            interfaceStep.from(table);
            generalStep.from(table);
            nullableStep.from(table);
            helperStep.from(table);
            order(selectable);
            equal(selectable, nullable);
            selector.apply(nonNull);
            equality.apply("Ada");
            expressionEquality.apply(nullable);
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
    String invalidInterfaceNullness =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidInterfaceNullness {
          static void query(QueryOperations operations, Selectable<String> selectable) {
            SelectFromStep<String> step = operations.select(selectable);
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
    assertFalse(
        compile(
            "samples.InvalidInterfaceNullness",
            invalidInterfaceNullness,
            temporaryDirectory.resolve("invalid-interface-nullness")));
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
              QueryCondition predicate) {
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
              QueryCondition predicate) {
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
  void usesOneRootNeutralConditionTypeForEveryConditionChain() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidChain {
          static <E> void query(
              SelectQuery<E, E> query,
              QueryCondition first,
              QueryCondition second) {
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
              QueryCondition pet,
              QueryCondition owner) {
            query.where(pet).and(owner);
          }
        }
        """;

    assertTrue(compile("samples.ValidChain", valid, temporaryDirectory.resolve("valid-chain")));
    assertTrue(
        compile("samples.WideChain", wide, temporaryDirectory.resolve("wide-chain")));
  }

  @Test
  void enforcesInvariantSingleColumnInSubqueryTypesAndRejectsProjectionShapes() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        import java.util.List;
        final class ValidInSubquery {
          static <V> QueryCondition membership(
              Selectable<V> left, SingleColumnSelect<V> right) {
            return left.in(right).and(left.notIn(right));
          }
          static <E> void query(
              NonNullQueryColumn<E, Long> id,
              NullableQueryColumn<E, Long> parentId,
              QueryTable<E> table) {
            NonNullSingleColumnSelect<Long> ids =
                Sql.select(id).from(table).where(id.isNotNull()).orderBy(id.asc()).distinct();
            SingleColumnSelect<Long> parentIds =
                Sql.select(parentId).from(table).orderBy(parentId.asc());
            QueryCondition first = membership(id, ids);
            QueryCondition second = parentId.notIn(parentIds);
            QueryCondition existingCollection = id.in(List.of(1L, 2L));
          }
        }
        """;
    String wrongType =
        """
        package samples;
        import io.skis.query.*;
        final class WrongInSubqueryType {
          static <E> void query(
              NonNullQueryColumn<E, Long> id,
              NonNullQueryColumn<E, Integer> number,
              QueryTable<E> table) {
            SingleColumnSelect<Integer> numbers = Sql.select(number).from(table);
            id.in(numbers);
          }
        }
        """;
    String projectionIsNotSingleColumn =
        """
        package samples;
        import io.skis.query.*;
        final class ProjectionIsNotSingleColumn {
          static final class Summary {}
          static <E> void query(
              NonNullQueryColumn<E, Long> id,
              QueryTable<E> table,
              ProjectionSelection<Long> projection) {
            NonNullSelectDescription<Long> description = Sql.select(projection).from(table);
            id.in(description);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidInSubquery",
            valid,
            temporaryDirectory.resolve("valid-in-subquery")));
    assertFalse(
        compile(
            "samples.WrongInSubqueryType",
            wrongType,
            temporaryDirectory.resolve("wrong-in-subquery-type")));
    assertFalse(
        compile(
            "samples.ProjectionIsNotSingleColumn",
            projectionIsNotSingleColumn,
            temporaryDirectory.resolve("projection-is-not-single-column")));
  }

  @Test
  void keepsScalarSubqueriesInvariantAndConservativelyNullable() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidScalarSubquery {
          static <E> void query(
              QueryOperations operations,
              NonNullQueryColumn<E, Long> id,
              QueryTable<E> table) {
            SingleColumnSelect<Long> ids = Sql.select(id).from(table);
            Selectable<Long> scalar = Sql.scalar(ids);
            NullableSelectFromStep<Long> selected = operations.select(scalar);
            QueryCondition comparison = scalar.eq(id);
            QueryCondition nullCheck = scalar.isNull();
            SortSpecification ordering = scalar.asc().nullsLast();
            selected.from(table).where(comparison.and(nullCheck)).orderBy(ordering);
          }
        }
        """;
    String invalidNonNull =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidNonNullScalarSubquery {
          static <E> void query(
              NonNullQueryColumn<E, Long> id,
              QueryTable<E> table) {
            NonNullSelectable<Long> scalar = Sql.scalar(Sql.select(id).from(table));
          }
        }
        """;
    String invalidType =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidScalarSubqueryType {
          static <E> void query(
              NonNullQueryColumn<E, Long> id,
              NonNullQueryColumn<E, String> name,
              QueryTable<E> table) {
            Selectable<Long> scalar = Sql.scalar(Sql.select(id).from(table));
            scalar.eq(name);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidScalarSubquery",
            valid,
            temporaryDirectory.resolve("valid-scalar-subquery")));
    assertFalse(
        compile(
            "samples.InvalidNonNullScalarSubquery",
            invalidNonNull,
            temporaryDirectory.resolve("invalid-non-null-scalar-subquery")));
    assertFalse(
        compile(
            "samples.InvalidScalarSubqueryType",
            invalidType,
            temporaryDirectory.resolve("invalid-scalar-subquery-type")));
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
  void entityAndDerivedJoinsShareOnStepTypesWithoutARightEntityPhantom() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class UnifiedJoinOnSteps {
          static final class Root {}
          static final class Joined {}

          static void queries(
              SelectQuery<Root, String> query,
              NullableSelectQuery<Root, String> nullableQuery,
              QueryTable<Joined> table,
              DerivedRelation derived,
              QueryCondition condition) {
            JoinOnStep<Root, String> tableStep = query.join(table);
            JoinOnStep<Root, String> derivedStep = query.join(derived);
            SelectQuery<Root, String> tableQuery = tableStep.on(condition);
            SelectQuery<Root, String> derivedQuery = derivedStep.on(condition);

            NullableJoinOnStep<Root, String> nullableTableStep = nullableQuery.join(table);
            NullableJoinOnStep<Root, String> nullableDerivedStep = nullableQuery.join(derived);
            NullableSelectQuery<Root, String> nullableTableQuery =
                nullableTableStep.on(condition);
            NullableSelectQuery<Root, String> nullableDerivedQuery =
                nullableDerivedStep.on(condition);
          }

          static void descriptions(
              SelectDescription<String> description,
              NonNullSelectDescription<String> nonNullDescription,
              SingleColumnSelect<String> singleColumn,
              NonNullSingleColumnSelect<String> nonNullSingleColumn,
              QueryTable<Joined> table,
              DerivedRelation derived,
              QueryCondition condition) {
            SelectDescriptionJoinOnStep<String> descriptionTableStep =
                description.join(table);
            SelectDescriptionJoinOnStep<String> descriptionDerivedStep =
                description.join(derived);
            SelectDescription<String> tableDescription = descriptionTableStep.on(condition);
            SelectDescription<String> derivedDescription = descriptionDerivedStep.on(condition);

            NonNullSelectDescriptionJoinOnStep<String> nonNullTableStep =
                nonNullDescription.join(table);
            NonNullSelectDescriptionJoinOnStep<String> nonNullDerivedStep =
                nonNullDescription.join(derived);
            NonNullSelectDescription<String> nonNullTableDescription =
                nonNullTableStep.on(condition);
            NonNullSelectDescription<String> nonNullDerivedDescription =
                nonNullDerivedStep.on(condition);

            SingleColumnSelectJoinOnStep<String> singleTableStep = singleColumn.join(table);
            SingleColumnSelectJoinOnStep<String> singleDerivedStep = singleColumn.join(derived);
            SingleColumnSelect<String> singleTableDescription = singleTableStep.on(condition);
            SingleColumnSelect<String> singleDerivedDescription = singleDerivedStep.on(condition);

            NonNullSingleColumnSelectJoinOnStep<String> nonNullSingleTableStep =
                nonNullSingleColumn.join(table);
            NonNullSingleColumnSelectJoinOnStep<String> nonNullSingleDerivedStep =
                nonNullSingleColumn.join(derived);
            NonNullSingleColumnSelect<String> nonNullSingleTableDescription =
                nonNullSingleTableStep.on(condition);
            NonNullSingleColumnSelect<String> nonNullSingleDerivedDescription =
                nonNullSingleDerivedStep.on(condition);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.UnifiedJoinOnSteps",
            valid,
            temporaryDirectory.resolve("unified-join-on-steps")));
  }

  @Test
  void allowsOrderByColumnsFromTheFinalJoinScope() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class JoinedOrdering {
          static final class Pet {}
          static final class Owner {}
          static void query(
              SelectQuery<Pet, Pet> query,
              QueryTable<Owner> owner,
              NonNullQueryColumn<Pet, Long> petId,
              NonNullQueryColumn<Owner, Long> ownerId,
              QueryCondition on) {
            query.join(owner).on(on).orderBy(ownerId.asc(), petId.asc());
          }
        }
        """;

    assertTrue(
        compile(
            "samples.JoinedOrdering",
            valid,
            temporaryDirectory.resolve("joined-ordering")));
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

  @Test
  void preservesDerivedOutputNullnessAndUsesANeutralDerivedRootType() throws Exception {
    String valid =
        """
        package samples;
        import io.skis.query.*;
        final class ValidDerivedRelation {
          static final class Pet {}
          static void query(
              QueryOperations operations,
              QueryTable<Pet> pet,
              NonNullQueryColumn<Pet, Long> id,
              NullableQueryColumn<Pet, String> name,
              QueryCondition on) {
            NonNullDerivedOutput<Long> idOutput = Sql.output(id, "pet_id");
            DerivedOutput<String> nameOutput = Sql.output(name, "display_name");
            DerivedRelation derived =
                Sql.derived(Sql.select(id).from(pet), "visible_pet", idOutput);
            NonNullSelectable<Long> derivedId = derived.column(idOutput);
            SelectQuery<?, Long> neutralRoot = operations.select(derivedId).from(derived);
            SelectQuery<Pet, Long> entityRoot =
                operations.select(derivedId).from(pet).join(derived).on(on);
            DerivedRelation nullableRelation =
                Sql.derived(Sql.select(name).from(pet), "named_pet", nameOutput);
            Selectable<String> derivedName = nullableRelation.column(nameOutput);
            NullableSelectQuery<?, String> nullableRoot =
                operations.select(derivedName).from(nullableRelation);
            neutralRoot.crossJoin(derived.as("other_pet"));
            entityRoot.leftJoin(nullableRelation).on(on);
            nullableRoot.where(derivedName.isNotNull());
          }
        }
        """;
    String invalidNullness =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidDerivedNullness {
          static final class Pet {}
          static void query(
              QueryTable<Pet> pet,
              NullableQueryColumn<Pet, String> name) {
            DerivedOutput<String> output = Sql.output(name, "display_name");
            DerivedRelation derived =
                Sql.derived(Sql.select(name).from(pet), "named_pet", output);
            NonNullSelectable<String> invalid = derived.column(output);
          }
        }
        """;
    String invalidRoot =
        """
        package samples;
        import io.skis.query.*;
        final class InvalidDerivedRoot {
          static final class Pet {}
          static void query(
              QueryOperations operations,
              QueryTable<Pet> pet,
              NonNullQueryColumn<Pet, Long> id) {
            NonNullDerivedOutput<Long> output = Sql.output(id, "pet_id");
            DerivedRelation derived =
                Sql.derived(Sql.select(id).from(pet), "visible_pet", output);
            SelectQuery<Pet, Long> invalid =
                operations.select(derived.column(output)).from(derived);
          }
        }
        """;

    assertTrue(
        compile(
            "samples.ValidDerivedRelation",
            valid,
            temporaryDirectory.resolve("valid-derived-relation")));
    assertFalse(
        compile(
            "samples.InvalidDerivedNullness",
            invalidNullness,
            temporaryDirectory.resolve("invalid-derived-nullness")));
    assertFalse(
        compile(
            "samples.InvalidDerivedRoot",
            invalidRoot,
            temporaryDirectory.resolve("invalid-derived-root")));
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
