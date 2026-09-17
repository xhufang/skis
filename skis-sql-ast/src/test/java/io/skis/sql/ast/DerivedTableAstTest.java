package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DerivedTableAstTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void resolvesPublicOutputsByConcreteSourceAndOrdinal() {
    PetTable inner = new PetTable().as("inner_pet");
    DerivedRelationReference reference =
        reference(
            "visible_pets",
            new DerivedOutputColumn(
                Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
            new DerivedOutputColumn(
                Identifier.of("display_name"),
                String.class,
                SqlType.VARCHAR,
                Nullability.NULLABLE));
    DerivedRelationSource source =
        new DerivedRelationSource(
            new SelectStatement(List.of(inner.id(), inner.name()), inner), reference);
    DerivedColumnExpression<String> name = new DerivedColumnExpression<>(reference, 1);
    QueryBlockAnalysis analysis =
        SemanticValidator.analyzeComplete(
            new SelectStatement(List.of(name), FromClause.of(source)));

    assertEquals(1, analysis.nestedBlocks().size());
    assertEquals(
        QueryBlockAnalysis.NestedQueryKind.DERIVED_TABLE,
        analysis.nestedBlocks().getFirst().kind());
    assertEquals(
        "$/JOIN source[0]#0",
        analysis.nestedBlocks().getFirst().analysis().path().toString());
    ResolvedDerivedColumnIdentity resolved =
        assertInstanceOf(
            ResolvedDerivedColumnIdentity.class,
            analysis.expressions().getFirst().columnDependencies().getFirst());
    assertEquals(0, resolved.source().occurrenceOrdinal());
    assertEquals(1, resolved.outputOrdinal());
    assertEquals("display_name", resolved.outputName());
  }

  @Test
  void freezesCompletedInnerNullabilityAndAppliesOuterNullExtensionAgain() {
    PetTable innerRoot = new PetTable().as("inner_root");
    PetTable innerRight = new PetTable().as("inner_right");
    FromClause innerFrom =
        new FromClause(
            innerRoot,
            List.of(
                new JoinClause(
                    JoinType.LEFT,
                    innerRight,
                    innerRoot.id().eq(innerRight.id()))));
    SelectStatement nullableInner =
        new SelectStatement(List.of(innerRight.id()), innerFrom);
    DerivedRelationReference incorrectlyNonNull =
        reference(
            "nullable_ids",
            new DerivedOutputColumn(
                Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL));
    DerivedRelationSource invalid =
        new DerivedRelationSource(nullableInner, incorrectlyNonNull);
    SelectStatement outer =
        new SelectStatement(
            List.of(new DerivedColumnExpression<Long>(incorrectlyNonNull, 0)),
            FromClause.of(invalid));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> SemanticValidator.analyzeComplete(outer));
    assertTrue(failure.getMessage().contains("effectively nullable"));

    PetTable outerRoot = new PetTable().as("outer_pet");
    PetTable stableInner = new PetTable().as("stable_inner");
    DerivedRelationReference stableReference =
        reference(
            "stable_ids",
            new DerivedOutputColumn(
                Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL));
    DerivedRelationSource stableSource =
        new DerivedRelationSource(
            new SelectStatement(List.of(stableInner.id()), stableInner), stableReference);
    DerivedColumnExpression<Long> stableId =
        new DerivedColumnExpression<>(stableReference, 0);
    FromClause leftJoined =
        new FromClause(
            outerRoot,
            List.of(
                new JoinClause(
                    JoinType.LEFT, stableSource, outerRoot.id().eq(stableId))));

    assertEquals(Nullability.NULLABLE, leftJoined.effectiveNullability(stableId));
    QueryBlockAnalysis leftAnalysis =
        SemanticValidator.analyzeComplete(new SelectStatement(List.of(stableId), leftJoined));
    assertEquals(
        Nullability.NULLABLE,
        expression(leftAnalysis, QueryClause.SELECT, 0).effectiveNullability());

    FromClause rightJoined =
        new FromClause(
            stableSource,
            List.of(
                new JoinClause(
                    JoinType.RIGHT,
                    outerRoot,
                    new ComparisonPredicate<>(
                        stableId, ComparisonOperator.EQUAL, outerRoot.id()))));
    assertEquals(Nullability.NULLABLE, rightJoined.effectiveNullability(stableId));

    FromClause fullJoined =
        new FromClause(
            outerRoot,
            List.of(
                new JoinClause(
                    JoinType.FULL, stableSource, outerRoot.id().eq(stableId))));
    assertEquals(Nullability.NULLABLE, fullJoined.effectiveNullability(outerRoot.id()));
    assertEquals(Nullability.NULLABLE, fullJoined.effectiveNullability(stableId));
  }

  @Test
  void rejectsBoundaryPiercingAndStructurallyEqualReferenceImpersonation() {
    PetTable outer = new PetTable().as("outer_pet");
    PetTable inner = new PetTable().as("inner_pet");
    DerivedRelationReference reference =
        reference(
            "derived_pet",
            new DerivedOutputColumn(
                Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL));
    DerivedRelationSource capturesOuter =
        new DerivedRelationSource(
            new SelectStatement(List.of(outer.id()), inner), reference);
    DerivedColumnExpression<Long> exposed = new DerivedColumnExpression<>(reference, 0);
    FromClause capturedFrom =
        new FromClause(
            outer,
            List.of(
                new JoinClause(
                    JoinType.INNER, capturesOuter, outer.id().eq(exposed))));

    IllegalArgumentException boundaryFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SemanticValidator.analyzeComplete(
                    new SelectStatement(List.of(exposed), capturedFrom)));
    assertTrue(boundaryFailure.getMessage().contains("non-correlated relation-source boundary"));

    DerivedRelationReference impersonator =
        reference(
            "derived_pet",
            new DerivedOutputColumn(
                Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL));
    assertEquals(reference, impersonator);
    SelectStatement innerStatement = new SelectStatement(List.of(inner.id()), inner);
    DerivedRelationSource source = new DerivedRelationSource(innerStatement, reference);
    DerivedColumnExpression<Long> wrong = new DerivedColumnExpression<>(impersonator, 0);

    IllegalArgumentException identityFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SemanticValidator.analyzeComplete(
                    new SelectStatement(List.of(wrong), FromClause.of(source))));
    assertTrue(identityFailure.getMessage().contains("matched by object identity"));
  }

  @Test
  void validatesExplicitAliasesAndKeepsAstEqualityIndependentOfReferenceIdentity() {
    DerivedOutputColumn id =
        new DerivedOutputColumn(
            Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
    List<DerivedOutputColumn> mutableOutputs = new ArrayList<>(List.of(id));
    DerivedRelationReference copied =
        new DerivedRelationReference(Identifier.of("copied_pets"), mutableOutputs);
    mutableOutputs.clear();
    assertEquals(List.of(id), copied.outputs());
    assertThrows(UnsupportedOperationException.class, () -> copied.outputs().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DerivedRelationReference(Identifier.of("pets"), List.of(id, id)));

    PetTable firstTable = new PetTable().as("inner_pet");
    PetTable secondTable = new PetTable().as("inner_pet");
    DerivedRelationSource first =
        new DerivedRelationSource(
            new SelectStatement(List.of(firstTable.id()), firstTable), reference("pets", id));
    DerivedRelationSource rebuilt =
        new DerivedRelationSource(
            new SelectStatement(List.of(secondTable.id()), secondTable), reference("pets", id));
    DerivedRelationSource differentAlias =
        new DerivedRelationSource(
            new SelectStatement(List.of(secondTable.id()), secondTable), reference("other_pets", id));

    assertEquals(first, rebuilt);
    assertEquals(first.hashCode(), rebuilt.hashCode());
    assertNotEquals(first, differentAlias);

    DerivedRelationReference longOutputAlias =
        reference(
            "pets",
            new DerivedOutputColumn(
                Identifier.of("a".repeat(64)),
                Long.class,
                SqlType.BIGINT,
                Nullability.NON_NULL));
    DerivedRelationSource nonPortable =
        new DerivedRelationSource(
            new SelectStatement(List.of(firstTable.id()), firstTable), longOutputAlias);
    IllegalArgumentException portabilityFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SemanticValidator.analyzeComplete(
                    new SelectStatement(
                        List.of(new DerivedColumnExpression<Long>(longOutputAlias, 0)),
                        FromClause.of(nonPortable))));
    assertTrue(portabilityFailure.getMessage().contains("portable derived output aliases"));
  }

  private static DerivedRelationReference reference(
      String alias, DerivedOutputColumn... outputs) {
    return new DerivedRelationReference(Identifier.of(alias), List.of(outputs));
  }

  private static ResolvedExpression expression(
      QueryBlockAnalysis analysis, QueryClause clause, int itemOrdinal) {
    return analysis.expressions().stream()
        .filter(
            expression ->
                expression.position().clause() == clause
                    && expression.position().itemOrdinal() == itemOrdinal)
        .findFirst()
        .orElseThrow();
  }

  private record Pet(Long id, String name) {}

  private static final class PetTable extends TableExpression<Pet> {

    private final ColumnExpression<Pet, Long> id = column(ID);
    private final ColumnExpression<Pet, String> name = column(NAME);

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
    }

    private ColumnExpression<Pet, Long> id() {
      return id;
    }

    private ColumnExpression<Pet, String> name() {
      return name;
    }

    @Override
    public PetTable as(String alias) {
      return new PetTable(Identifier.of(alias));
    }

    @Override
    public PetTable as(Identifier alias) {
      return new PetTable(alias);
    }
  }
}
