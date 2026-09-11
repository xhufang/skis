package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryScopeAnalysisTest {

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
  void assignsStableBlockOccurrenceColumnAndParameterIdentities() {
    PetTable firstRoot = new PetTable();
    PetTable firstJoined = firstRoot.as("joined_pet");
    SelectStatement first =
        statementWithParameter(firstRoot, firstJoined, new ParameterSlot<>(0, Long.class, false));

    PetTable rebuiltRoot = new PetTable();
    PetTable rebuiltJoined = rebuiltRoot.as("joined_pet");
    SelectStatement rebuilt =
        statementWithParameter(
            rebuiltRoot, rebuiltJoined, new ParameterSlot<>(0, Long.class, false));

    QueryBlockAnalysis firstAnalysis = SemanticValidator.analyzeComplete(first);
    QueryBlockAnalysis rebuiltAnalysis = SemanticValidator.analyzeComplete(rebuilt);

    assertEquals(QueryBlockPath.root(), firstAnalysis.path());
    assertEquals(
        List.of(
            new ResolvedSourceIdentity(QueryBlockPath.root(), 0),
            new ResolvedSourceIdentity(QueryBlockPath.root(), 1)),
        firstAnalysis.sourceOccurrences().stream()
            .map(QueryBlockAnalysis.SourceOccurrence::identity)
            .toList());
    assertFalse(firstAnalysis.sourceOccurrences().getFirst().nullExtended());
    assertTrue(firstAnalysis.sourceOccurrences().get(1).nullExtended());
    assertEquals(firstAnalysis.structureKey(), rebuiltAnalysis.structureKey());
    assertEquals(firstAnalysis.expressions(), rebuiltAnalysis.expressions());

    ResolvedExpression where = expression(firstAnalysis, QueryClause.WHERE, 0);
    assertEquals(2, where.columnDependencies().size());
    assertEquals(1, where.parameterDependencies().size());
    assertEquals(QueryBlockPath.root(), where.parameterDependencies().getFirst().blockPath());
    assertEquals(0, where.parameterDependencies().getFirst().parameterOrdinal());
    assertEquals(Nullability.NON_NULL, where.effectiveNullability());
  }

  @Test
  void resolvesAncestorReferencesPerEmbeddingWithoutWritingBackToAst() {
    PetTable outer = new PetTable().as("owner_pet");
    SelectStatement parent =
        new SelectStatement(List.of(outer.id()), outer, outer.id().isNotNull());
    QueryBlockAnalysis parentAnalysis = SemanticValidator.analyzeComplete(parent);

    PetTable inner = new PetTable().as("child_pet");
    SelectStatement child =
        new SelectStatement(List.of(inner.id()), inner, inner.id().eq(outer.id()));
    int originalHash = child.hashCode();

    QueryBlockAnalysis inWhere =
        parentAnalysis.analyzeChild(child, QueryBlockLocation.where(0));
    QueryBlockAnalysis inSelect =
        parentAnalysis.analyzeChild(child, QueryBlockLocation.select(0, 0));

    assertEquals("$/WHERE[0]#0", inWhere.path().toString());
    assertEquals("$/SELECT[0]#0", inSelect.path().toString());
    assertNotEquals(inWhere.structureKey(), inSelect.structureKey());
    assertEquals(originalHash, child.hashCode());
    assertSame(inner, child.from());

    ResolvedExpression where = expression(inWhere, QueryClause.WHERE, 0);
    assertEquals(
        List.of(inWhere.path(), QueryBlockPath.root()),
        where.columnDependencies().stream()
            .map(dependency -> dependency.source().blockPath())
            .toList());

    IllegalArgumentException standalone =
        assertThrows(
            IllegalArgumentException.class, () -> SemanticValidator.analyzeComplete(child));
    assertTrue(standalone.getMessage().contains("query block"));
    assertTrue(standalone.getMessage().contains("WHERE item #0 operand #1"));
    assertTrue(standalone.getMessage().contains("unresolved outer reference"));
  }

  @Test
  void joinOnChildSeesOnlyItsStageAndUsesPreJoinNullability() {
    PetTable root = new PetTable();
    PetTable currentRight = root.as("current_right");
    PetTable futureRight = root.as("future_right");
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(
                    JoinType.LEFT, currentRight, root.id().eq(currentRight.id())),
                new JoinClause(
                    JoinType.INNER, futureRight, currentRight.id().eq(futureRight.id()))));
    SelectStatement parent = new SelectStatement(List.of(currentRight.id()), from);
    QueryBlockAnalysis parentAnalysis = SemanticValidator.analyzeComplete(parent);

    PetTable inner = root.as("inner_pet");
    SelectStatement currentCorrelation =
        new SelectStatement(
            List.of(inner.id()), inner, inner.id().eq(currentRight.id()));

    QueryBlockAnalysis inFirstOn =
        parentAnalysis.analyzeChild(currentCorrelation, QueryBlockLocation.joinOn(1, 0));
    QueryBlockAnalysis inFinalSelect =
        parentAnalysis.analyzeChild(currentCorrelation, QueryBlockLocation.select(0, 0));

    assertEquals(
        Nullability.NON_NULL,
        expression(inFirstOn, QueryClause.WHERE, 0).effectiveNullability());
    assertEquals(
        1,
        expression(inFirstOn, QueryClause.WHERE, 0)
            .columnDependencies()
            .get(1)
            .source()
            .occurrenceOrdinal());
    assertEquals(
        Nullability.NULLABLE,
        expression(inFinalSelect, QueryClause.WHERE, 0).effectiveNullability());

    SelectStatement futureCorrelation =
        new SelectStatement(
            List.of(inner.id()), inner, inner.id().eq(futureRight.id()));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parentAnalysis.analyzeChild(
                    futureCorrelation, QueryBlockLocation.joinOn(1, 1)));
    assertTrue(failure.getMessage().contains("JOIN ON"));
    assertTrue(failure.getMessage().contains("source occurrence #2 before it is visible"));
  }

  @Test
  void rejectsRepeatedVisibleTableObjectAndStructurallyEqualImpersonator() {
    PetTable outer = new PetTable().as("outer_pet");
    SelectStatement parent = new SelectStatement(List.of(outer.id()), outer);
    QueryBlockAnalysis parentAnalysis = SemanticValidator.analyzeComplete(parent);

    SelectStatement repeated = new SelectStatement(List.of(outer.id()), outer);
    IllegalArgumentException repeatedFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> parentAnalysis.analyzeChild(repeated, QueryBlockLocation.select(0, 0)));
    assertTrue(repeatedFailure.getMessage().contains("reuses the same table-expression object"));
    assertTrue(repeatedFailure.getMessage().contains("create an independent alias instance"));

    PetTable impersonator = new PetTable().as("outer_pet");
    assertEquals(outer, impersonator);
    PetTable inner = new PetTable().as("inner_pet");
    SelectStatement wrongReference =
        new SelectStatement(
            List.of(inner.id()), inner, inner.id().eq(impersonator.id()));
    IllegalArgumentException impersonatorFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parentAnalysis.analyzeChild(
                    wrongReference, QueryBlockLocation.select(0, 1)));
    assertTrue(impersonatorFailure.getMessage().contains("object identity"));
    assertTrue(impersonatorFailure.getMessage().contains("not visible"));
  }

  @Test
  void siblingSourcesNeverLeakIntoAnotherChildScope() {
    PetTable outer = new PetTable().as("outer_pet");
    QueryBlockAnalysis parentAnalysis =
        SemanticValidator.analyzeComplete(new SelectStatement(List.of(outer.id()), outer));
    PetTable firstSibling = new PetTable().as("first_sibling");
    PetTable secondSibling = new PetTable().as("second_sibling");
    SelectStatement second =
        new SelectStatement(
            List.of(secondSibling.id()),
            secondSibling,
            secondSibling.id().eq(firstSibling.id()));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> parentAnalysis.analyzeChild(second, QueryBlockLocation.select(0, 1)));

    assertTrue(failure.getMessage().contains("not visible in the current or any ancestor"));
    assertTrue(failure.getMessage().contains("first_sibling.id"));
  }

  @Test
  void detectsOnlyHarmfulQualifierShadowing() {
    PetTable outer = new PetTable().as("shared_name");
    QueryBlockAnalysis parentAnalysis =
        SemanticValidator.analyzeComplete(new SelectStatement(List.of(outer.id()), outer));
    PetTable shadow = new PetTable().as("shared_name");
    SelectStatement correlated =
        new SelectStatement(List.of(shadow.id()), shadow, shadow.id().eq(outer.id()));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> parentAnalysis.analyzeChild(correlated, QueryBlockLocation.select(0, 0)));
    assertTrue(failure.getMessage().contains("effective qualifier 'shared_name' is shadowed"));
    assertTrue(failure.getMessage().contains("source[0]"));

    SelectStatement uncorrelated =
        new SelectStatement(List.of(shadow.id()), shadow, shadow.id().isNotNull());
    QueryBlockAnalysis valid =
        parentAnalysis.analyzeChild(uncorrelated, QueryBlockLocation.select(0, 1));
    assertFalse(valid.expressions().isEmpty());
  }

  @Test
  void rejectsQualifiersThatPostgresqlWouldTruncate() {
    PetTable boundary = new PetTable().as("a".repeat(63));
    QueryBlockAnalysis boundaryAnalysis =
        SemanticValidator.analyzeComplete(
            new SelectStatement(List.of(boundary.id()), boundary));
    assertFalse(boundaryAnalysis.expressions().isEmpty());

    PetTable overlong = new PetTable().as("a".repeat(64));
    SelectStatement statement = new SelectStatement(List.of(overlong.id()), overlong);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> SemanticValidator.analyzeComplete(statement));

    assertTrue(failure.getMessage().contains("query block $ source occurrence #0"));
    assertTrue(failure.getMessage().contains("must not exceed 63 bytes"));
    assertTrue(failure.getMessage().contains("use a shorter alias"));
  }

  @Test
  void rejectsNestedAnalysisAtANonexistentCrossJoinOnLocation() {
    PetTable root = new PetTable();
    PetTable crossed = root.as("crossed_pet");
    QueryBlockAnalysis parentAnalysis =
        SemanticValidator.analyzeComplete(
            new SelectStatement(
                List.of(root.id()),
                new FromClause(
                    root, List.of(new JoinClause(JoinType.CROSS, crossed, null)))));
    PetTable inner = root.as("inner_pet");
    SelectStatement child = new SelectStatement(List.of(inner.id()), inner);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parentAnalysis.analyzeChild(
                    child, QueryBlockLocation.joinOn(1, 0)));

    assertTrue(failure.getMessage().contains("has no JOIN ON item #1"));
  }

  @Test
  void resolvesLegalMultiLevelCorrelationAndRejectsNonCorrelatedSourceBoundary() {
    PetTable outer = new PetTable().as("outer_pet");
    SelectStatement outerStatement = new SelectStatement(List.of(outer.id()), outer);
    QueryBlockAnalysis outerAnalysis = SemanticValidator.analyzeComplete(outerStatement);

    PetTable middle = new PetTable().as("middle_pet");
    SelectStatement middleStatement =
        new SelectStatement(List.of(middle.id()), middle, middle.id().isNotNull());
    QueryBlockAnalysis middleAnalysis =
        outerAnalysis.analyzeChild(middleStatement, QueryBlockLocation.select(0, 0));

    PetTable inner = new PetTable().as("inner_pet");
    SelectStatement innerStatement =
        new SelectStatement(List.of(inner.id()), inner, inner.id().eq(outer.id()));
    QueryBlockAnalysis innerAnalysis =
        middleAnalysis.analyzeChild(innerStatement, QueryBlockLocation.where(0));

    ResolvedExpression innerWhere = expression(innerAnalysis, QueryClause.WHERE, 0);
    assertEquals(
        QueryBlockPath.root(), innerWhere.columnDependencies().get(1).source().blockPath());

    PetTable joined = outer.as("joined_pet");
    SelectStatement parentWithJoin =
        new SelectStatement(
            List.of(outer.id()),
            new FromClause(
                outer,
                List.of(
                    new JoinClause(JoinType.INNER, joined, outer.id().eq(joined.id())))));
    QueryBlockAnalysis joinAnalysis = SemanticValidator.analyzeComplete(parentWithJoin);
    IllegalArgumentException boundaryFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                joinAnalysis.analyzeChild(
                    innerStatement,
                    QueryBlockLocation.relationSource(1, 0)));
    assertTrue(boundaryFailure.getMessage().contains("non-correlated relation-source boundary"));
  }

  @Test
  void finalClauseSnapshotsCarryTheCompletedSourceScope() {
    PetTable outer = new PetTable().as("outer_pet");
    SelectStatement parent =
        new SelectStatement(
            false,
            List.of(outer.id()),
            List.of(),
            outer,
            outer.id().isNotNull(),
            List.of(outer.id()),
            outer.id().isNotNull(),
            List.of(
                new OrderByItem(
                    outer.id(), OrderDirection.ASC, NullOrder.DIALECT_DEFAULT)),
            new Limit(new ParameterSlot<>(0, Integer.class, false)));
    QueryBlockAnalysis parentAnalysis = SemanticValidator.analyzeComplete(parent);
    PetTable inner = new PetTable().as("inner_pet");
    SelectStatement child =
        new SelectStatement(List.of(inner.id()), inner, inner.id().eq(outer.id()));

    List<QueryBlockLocation> locations =
        List.of(
            QueryBlockLocation.select(0, 0),
            QueryBlockLocation.where(0),
            QueryBlockLocation.groupBy(0, 0),
            QueryBlockLocation.having(0),
            QueryBlockLocation.orderBy(0, 0),
            QueryBlockLocation.pagination(0, 0));
    for (QueryBlockLocation location : locations) {
      QueryBlockAnalysis childAnalysis = parentAnalysis.analyzeChild(child, location);
      ResolvedExpression childWhere = expression(childAnalysis, QueryClause.WHERE, 0);
      assertEquals(
          QueryBlockPath.root(),
          childWhere.columnDependencies().get(1).source().blockPath());
    }
  }

  @Test
  void analysisContainsPathClauseOccurrenceAndParameterDependencies() {
    PetTable root = new PetTable();
    PetTable future = root.as("future_pet");
    ParameterSlot<Long> parameter = new ParameterSlot<>(0, Long.class, false);
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(
                    JoinType.INNER,
                    future,
                    new LogicalPredicate(
                        LogicalOperator.AND,
                        List.of(
                            root.id().eq(future.id()),
                            root.id().eq(parameter))))));
    SelectStatement statement = new SelectStatement(List.of(root.id()), from);
    QueryBlockAnalysis analysis = SemanticValidator.analyzeComplete(statement);

    ResolvedExpression on = expression(analysis, QueryClause.JOIN_ON, 1);
    assertEquals(QueryBlockPath.root(), on.position().blockPath());
    assertEquals(0, on.columnDependencies().getFirst().source().occurrenceOrdinal());
    assertEquals(1, on.columnDependencies().get(1).source().occurrenceOrdinal());
    assertEquals(1, on.parameterDependencies().size());
  }

  @Test
  void rejectsUnknownExpressionsInsteadOfTreatingThemAsOpaqueLeaves() {
    PetTable root = new PetTable();
    UnknownExpression unknown = new UnknownExpression(root.id());

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SelectStatement(List.of(unknown), root));

    assertTrue(failure.getMessage().contains("SELECT uses unsupported SQL expression node"));
    assertTrue(failure.getMessage().contains(UnknownExpression.class.getName()));

    IllegalArgumentException nullabilityFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> FromClause.of(root).effectiveNullability(unknown));
    assertTrue(nullabilityFailure.getMessage().contains(UnknownExpression.class.getName()));
  }

  private static SelectStatement statementWithParameter(
      PetTable root, PetTable joined, ParameterSlot<Long> parameter) {
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.LEFT, joined, root.id().eq(joined.id()))));
    return new SelectStatement(
        false,
        List.of(joined.name()),
        List.of(),
        from,
        new LogicalPredicate(
            LogicalOperator.AND,
            List.of(root.id().eq(parameter), joined.name().isNotNull())),
        List.of(
            new OrderByItem(joined.id(), OrderDirection.DESC, NullOrder.LAST)),
        null);
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

  private record UnknownExpression(SqlExpression<Long> hidden) implements SqlExpression<Long> {

    private UnknownExpression {
      java.util.Objects.requireNonNull(hidden, "hidden");
    }

    @Override
    public Class<Long> javaType() {
      return Long.class;
    }

    @Override
    public SqlType sqlType() {
      return SqlType.BIGINT;
    }

    @Override
    public Nullability nullability() {
      return Nullability.NON_NULL;
    }

    @Override
    public boolean nullable() {
      return false;
    }
  }

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
