package io.skis.dialect.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics.QuerySemanticReason;
import io.skis.core.SkisErrorCode;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.ExpressionInfo;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.Occurrence;
import io.skis.dialect.spi.ParameterLayout;
import io.skis.dialect.spi.RenderInput;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.ExistsPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.QueryBlockPath;
import io.skis.sql.ast.QueryClause;
import io.skis.sql.ast.ResolvedColumnIdentity;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.TableExpression;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T07 controlled-stage, target-binding, and final-layout contracts. */
class CompilationPipelineTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          TableMeta.of("pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void stageTypesAreFinalAndHaveNoPublicConstructors() {
    for (Class<?> type :
        List.of(
            ResolvedStatement.class,
            RenderInput.class,
            Occurrence.class,
            ExpressionInfo.class,
            ParameterLayout.class)) {
      assertTrue(Modifier.isFinal(type.getModifiers()), type.getName() + " must be final");
      assertEquals(
          0, type.getConstructors().length, type.getName() + " must not expose a constructor");
    }
  }

  @Test
  void sameNodeAtDifferentPositionsReceivesDistinctOccurrences() {
    PetTable pet = new PetTable(PET);
    ParameterSlot<Long> shared = new ParameterSlot<>(0, Long.class, false);
    SelectStatement statement = new SelectStatement(List.of(shared, shared), pet);
    ResolvedStatement resolved = ResolvedStatement.analyze(statement);

    Occurrence first =
        resolved.occurrenceAt(
            io.skis.sql.ast.QueryBlockPath.root(), io.skis.sql.ast.QueryClause.SELECT, 0);
    Occurrence second =
        resolved.occurrenceAt(
            io.skis.sql.ast.QueryBlockPath.root(), io.skis.sql.ast.QueryClause.SELECT, 1);

    assertEquals(0, first.itemOrdinal());
    assertEquals(1, second.itemOrdinal());
    assertEquals(first.expression(), second.expression());
    assertEquals(first.info().javaType(), second.info().javaType());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            resolved.occurrenceAt(
                io.skis.sql.ast.QueryBlockPath.root(), io.skis.sql.ast.QueryClause.SELECT, 2));

    ResolvedStatement other =
        ResolvedStatement.analyze(new SelectStatement(List.of(pet.id()), pet));
    assertThrows(IllegalArgumentException.class, () -> other.expressionInfo(first));
  }

  @Test
  void validationLoweringNormalizationAndRenderingRunInControlledOrder() {
    TrackingDialect dialect = new TrackingDialect("order-test", false, false);
    PetTable pet = new PetTable(PET);
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    SelectStatement statement = new SelectStatement(List.of(pet.id()), pet, pet.id().eq(id));

    SqlTemplate template = SqlCompiler.compile(statement, dialect);

    assertEquals(List.of("validate", "lower", "render"), dialect.events);
    assertEquals(1, template.parameterCount());
    assertEquals(0, template.parameterLayout().slots().getFirst().ordinal());
  }

  @Test
  void aggregateStructureFlagDistinguishesMutationPlainGroupedAndNestedQueries() {
    PetTable pet = new PetTable(PET);
    assertFalse(
        ResolvedStatement.analyze(new DeleteStatement(pet, new BooleanPredicate(true)))
            .hasAggregateStructure());
    assertFalse(
        ResolvedStatement.analyze(new SelectStatement(List.of(pet.id()), pet))
            .hasAggregateStructure());
    assertTrue(
        ResolvedStatement.analyze(new SelectStatement(List.of(AggregateExpression.countAll()), pet))
            .hasAggregateStructure());
    SelectStatement grouped =
        new SelectStatement(
            false, List.of(pet.id()), List.of(), new FromClause(pet, List.of()),
            null, List.of(pet.id()), null, List.of(), null);
    assertTrue(ResolvedStatement.analyze(grouped).hasAggregateStructure());
    PetTable inner = pet.as(Identifier.of("aggregate_inner"));
    SelectStatement nested =
        new SelectStatement(
            List.of(pet.id()), pet,
            new ExistsPredicate(new SelectStatement(List.of(AggregateExpression.countAll()), inner), false));
    assertTrue(ResolvedStatement.analyze(nested).hasAggregateStructure());
  }

  @Test
  void legalAggregatesRetainOccurrencesDependenciesAndEffectiveNullabilityInResolvedStage() {
    PetTable pet = new PetTable(PET, Identifier.of("p"));
    PetTable joined = new PetTable(PET, Identifier.of("j"));
    ParameterSlot<Long> parameter = new ParameterSlot<>(0, Long.class, false);
    AggregateExpression<Long> count = AggregateExpression.count(joined.id());
    AggregateExpression<Long> maximum = AggregateExpression.max(joined.id());
    SelectStatement statement =
        new SelectStatement(
            false,
            List.of(
                pet.id(),
                count,
                maximum,
                AggregateExpression.max(parameter),
                AggregateExpression.countAll()),
            List.of(new HiddenSelection(count, Identifier.of("hidden_count"))),
            new FromClause(
                pet, List.of(new JoinClause(JoinType.LEFT, joined, pet.id().eq(joined.id())))),
            pet.id().gt(parameter),
            List.of(pet.id()),
            new ComparisonPredicate<>(
                count, ComparisonOperator.GREATER_THAN, LiteralExpression.zero(Long.class)),
            List.of(new OrderByItem(maximum, OrderDirection.ASC, NullOrder.LAST)),
            null);

    ResolvedStatement resolved = ResolvedStatement.analyze(statement);
    QueryBlockPath root = QueryBlockPath.root();
    Occurrence counted = resolved.occurrenceAt(root, QueryClause.SELECT, 1);
    Occurrence hidden = resolved.occurrenceAt(root, QueryClause.SELECT, 5);
    ExpressionInfo countInfo = resolved.expressionInfo(counted);
    ExpressionInfo maxInfo =
        resolved.expressionInfo(resolved.occurrenceAt(root, QueryClause.SELECT, 2));
    ExpressionInfo parameterInfo =
        resolved.expressionInfo(resolved.occurrenceAt(root, QueryClause.SELECT, 3));

    assertTrue(resolved.hasQueryBlockAnalysis());
    assertEquals(
        SemanticValidator.analyzeComplete(statement).structureKey(),
        resolved.structureKey().orElseThrow());
    assertEquals(2, resolved.sourceOccurrences().size());
    assertTrue(resolved.sourceOccurrences().get(1).nullExtended());
    assertSame(count, counted.expression());
    assertSame(count, hidden.expression());
    assertNotSame(counted, hidden);
    assertEquals(Long.class, countInfo.javaType());
    assertEquals(SqlType.BIGINT, countInfo.sqlType());
    assertEquals(Nullability.NON_NULL, countInfo.effectiveNullability());
    assertEquals(Nullability.NULLABLE, maxInfo.effectiveNullability());
    assertFalse(countInfo.correlated());
    assertEquals(1, countInfo.columnDependencies().size());
    ResolvedColumnIdentity column =
        (ResolvedColumnIdentity) countInfo.columnDependencies().getFirst();
    assertEquals(root, column.source().blockPath());
    assertEquals(1, column.source().occurrenceOrdinal());
    assertEquals(ID.ordinal(), column.propertyOrdinal());
    assertTrue(countInfo.parameterDependencies().isEmpty());
    assertEquals(countInfo.columnDependencies(), maxInfo.columnDependencies());
    assertEquals(countInfo.columnDependencies(), hidden.info().columnDependencies());
    assertTrue(parameterInfo.columnDependencies().isEmpty());
    assertEquals(Nullability.NULLABLE, parameterInfo.effectiveNullability());
    assertEquals(1, parameterInfo.parameterDependencies().size());
    assertEquals(0, parameterInfo.parameterDependencies().getFirst().parameterOrdinal());
    assertEquals(root, parameterInfo.parameterDependencies().getFirst().blockPath());
    ExpressionInfo countAllInfo =
        resolved.expressionInfo(resolved.occurrenceAt(root, QueryClause.SELECT, 4));
    assertTrue(countAllInfo.columnDependencies().isEmpty());
    assertTrue(countAllInfo.parameterDependencies().isEmpty());
    assertEquals(Nullability.NON_NULL, countAllInfo.effectiveNullability());
    assertEquals(
        parameterInfo.parameterDependencies(),
        resolved.occurrenceAt(root, QueryClause.WHERE, 0).info().parameterDependencies());
    ExpressionInfo groupInfo = resolved.occurrenceAt(root, QueryClause.GROUP_BY, 0).info();
    ExpressionInfo orderInfo = resolved.occurrenceAt(root, QueryClause.ORDER_BY, 0).info();
    assertTrue(groupInfo.parameterDependencies().isEmpty());
    assertTrue(orderInfo.parameterDependencies().isEmpty());
  }

  @Test
  void groupingFailureIsPreservedAcrossCompleteAnalysisAndCompilation() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement =
        new SelectStatement(List.of(pet.name(), AggregateExpression.countAll()), pet);
    TrackingDialect dialect = new TrackingDialect("grouping-failure", false, false);

    QueryValidationException direct =
        assertThrows(
            QueryValidationException.class, () -> SemanticValidator.validateComplete(statement));
    QueryValidationException resolved =
        assertThrows(
            QueryValidationException.class, () -> ResolvedStatement.analyze(statement));
    QueryValidationException compiled =
        assertThrows(
            QueryValidationException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.QUERY_GROUPING_CONTRACT, direct.code());
    assertEquals(direct.code(), resolved.code());
    assertEquals(direct.code(), compiled.code());
    assertEquals(direct.diagnostics(), resolved.diagnostics());
    assertEquals(direct.diagnostics(), compiled.diagnostics());
    assertTrue(dialect.events.isEmpty());
  }

  @Test
  void aggregateFailureIsDetectedBeforeEmptyInOrBooleanPruning() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement =
        new SelectStatement(
            List.of(pet.id()),
            pet,
            new LogicalPredicate(
                LogicalOperator.AND,
                List.of(
                    new BooleanPredicate(false),
                    new InPredicate<>(AggregateExpression.countAll(), List.of(), false))));
    TrackingDialect dialect = new TrackingDialect("aggregate-failure", false, false);

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.QUERY_AGGREGATE_CONTRACT, failure.code());
    assertEquals(
        QuerySemanticReason.AGGREGATE_IN_CLAUSE, failure.diagnostics().querySemantics().reason());
    assertTrue(dialect.events.isEmpty());
  }

  @Test
  void groupingFailureIntroducedByLoweringKeepsItsSemanticCode() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement = new SelectStatement(List.of(pet.id()), pet);
    TrackingDialect dialect =
        new TrackingDialect(
            "lowered-grouping-failure",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              return new SelectStatement(
                  select.distinct(),
                  select.selections(),
                  select.hiddenSelections(),
                  select.fromClause(),
                  select.where().orElse(null),
                  List.of(pet.name()),
                  select.having().orElse(null),
                  select.orderBy(),
                  select.pagination().orElse(null));
            });

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.QUERY_GROUPING_CONTRACT, failure.code());
    assertEquals(
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        failure.diagnostics().querySemantics().reason());
    assertEquals(List.of("validate", "lower"), dialect.events);
  }

  @Test
  void identityLoweringStillRunsPostLoweringValidationBeforeRendering() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement = new SelectStatement(List.of(pet.id()), pet);
    TrackingDialect dialect =
        new TrackingDialect(
            "post-validation-test",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              return new SelectStatement(
                  select.distinct(),
                  select.selections(),
                  select.hiddenSelections(),
                  select.fromClause(),
                  select.where().orElse(null),
                  List.of(select.selections().getFirst()),
                  select.having().orElse(null),
                  select.orderBy(),
                  select.pagination().orElse(null));
            });

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, failure.code());
    assertEquals("GROUP_BY", failure.diagnostics().dialect().missingCapability());
    assertEquals(List.of("validate", "lower"), dialect.events);
  }

  @Test
  void illegalLoweringCannotIntroduceAnUnknownParameterIdentity() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement = new SelectStatement(List.of(pet.id()), pet);
    TrackingDialect dialect =
        new TrackingDialect(
            "illegal-lowering-test",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              ParameterSlot<Long> introduced = new ParameterSlot<>(7, Long.class, false);
              return new SelectStatement(
                  select.selections(),
                  select.fromClause(),
                  new ComparisonPredicate<>(
                      pet.id(), io.skis.sql.ast.ComparisonOperator.EQUAL, introduced));
            });

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect));

    assertTrue(failure.code() == SkisErrorCode.DIALECT_INVALID_STRUCTURE);
    assertFalse(dialect.events.contains("render"));
  }

  @Test
  void postLoweringAnalysisRejectsANewOutOfScopeColumnBeforeRendering() {
    PetTable pet = new PetTable(PET);
    PetTable outside = pet.as(Identifier.of("outside"));
    SelectStatement statement = new SelectStatement(List.of(pet.id()), pet);
    TrackingDialect dialect =
        new TrackingDialect(
            "post-lowering-scope-test",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              return new SelectStatement(
                  select.selections(),
                  select.fromClause(),
                  outside.id().eq(LiteralExpression.one(Long.class)));
            });

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    assertEquals(List.of("validate", "lower"), dialect.events);
  }

  @Test
  void loweringCannotChangeTheStatementKind() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement = new SelectStatement(List.of(pet.id()), pet);
    TrackingDialect dialect =
        new TrackingDialect(
            "statement-kind-test",
            false,
            false,
            input -> new DeleteStatement(pet, new BooleanPredicate(true)));

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    assertEquals(List.of("validate", "lower"), dialect.events);
  }

  @Test
  void loweringCannotReorderEqualTypedVisibleSelections() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement =
        new SelectStatement(List.of(pet.id(), LiteralExpression.one(Long.class)), pet);
    TrackingDialect dialect =
        new TrackingDialect(
            "result-order-test",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              return new SelectStatement(
                  List.of(select.selections().get(1), select.selections().get(0)),
                  select.fromClause());
            });

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    assertTrue(failure.getMessage().contains("output correspondence"));
    assertFalse(dialect.events.contains("render"));
  }

  @Test
  void loweringCannotChangeEffectiveResultNullability() {
    PetTable root = new PetTable(PET).as(Identifier.of("root_pet"));
    PetTable joined = new PetTable(PET).as(Identifier.of("joined_pet"));
    ComparisonPredicate<Long> joinPredicate = root.id().eq(joined.id());
    SelectStatement statement =
        new SelectStatement(
            List.of(joined.id()),
            new FromClause(root, List.of(new JoinClause(JoinType.INNER, joined, joinPredicate))));
    TrackingDialect dialect =
        new TrackingDialect(
            "result-nullability-test",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              return new SelectStatement(
                  select.selections(),
                  new FromClause(
                      root, List.of(new JoinClause(JoinType.LEFT, joined, joinPredicate))));
            });

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    assertTrue(failure.getMessage().contains("visible SELECT item descriptor"));
    assertFalse(dialect.events.contains("render"));
  }

  @Test
  void loweringCannotReorderHiddenSelections() {
    PetTable pet = new PetTable(PET);
    List<HiddenSelection> hidden =
        List.of(
            new HiddenSelection(pet.id(), Identifier.of("hidden_id")),
            new HiddenSelection(LiteralExpression.one(Long.class), Identifier.of("hidden_one")));
    SelectStatement statement =
        new SelectStatement(false, List.of(pet.name()), hidden, pet, null, List.of(), null);
    TrackingDialect dialect =
        new TrackingDialect(
            "hidden-order-test",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              return new SelectStatement(
                  select.distinct(),
                  select.selections(),
                  List.of(select.hiddenSelections().get(1), select.hiddenSelections().get(0)),
                  select.fromClause(),
                  select.where().orElse(null),
                  select.orderBy(),
                  select.pagination().orElse(null));
            });

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(statement, dialect));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    assertTrue(failure.getMessage().contains("output correspondence"));
    assertFalse(dialect.events.contains("render"));
  }

  @Test
  void selectedOutputReferenceIsRejectedOutsideDirectDistinctOrdering() {
    PetTable pet = new PetTable(PET);
    SelectedOutputReference<Long> reference = SelectedOutputReference.of(1, pet.id());
    SelectStatement selectedReference = new SelectStatement(List.of(reference), pet);
    SelectStatement nonDistinctOrder =
        new SelectStatement(
            false,
            List.of(pet.id()),
            List.of(),
            pet,
            null,
            List.of(new OrderByItem(reference, OrderDirection.ASC, NullOrder.DIALECT_DEFAULT)),
            null);
    TrackingDialect dialect = new TrackingDialect("selected-output-context-test", false, true);

    SqlRenderException selectionFailure =
        assertThrows(
            SqlRenderException.class, () -> SqlCompiler.compile(selectedReference, dialect));
    SqlRenderException orderFailure =
        assertThrows(
            SqlRenderException.class, () -> SqlCompiler.compile(nonDistinctOrder, dialect));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, selectionFailure.code());
    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, orderFailure.code());
  }

  @Test
  void selectedOutputReferenceRequiresExactPositionAndNullability() {
    PetTable pet = new PetTable(PET);
    SelectStatement outOfRange = distinctOrderBy(pet, SelectedOutputReference.of(2, pet.name()));
    SelectedOutputReference<String> wrongNullability =
        new SelectedOutputReference<>(1, String.class, pet.name().sqlType(), Nullability.NON_NULL);
    SelectStatement wrongDescriptor = distinctOrderBy(pet, wrongNullability);
    TrackingDialect dialect = new TrackingDialect("selected-output-descriptor-test", false, true);

    SqlRenderException positionFailure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(outOfRange, dialect));
    SqlRenderException descriptorFailure =
        assertThrows(SqlRenderException.class, () -> SqlCompiler.compile(wrongDescriptor, dialect));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, positionFailure.code());
    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, descriptorFailure.code());
  }

  @Test
  void standardLoweringDefersPortableNormalizationToThePipeline() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement =
        new SelectStatement(List.of(pet.id()), pet, new InPredicate<>(pet.id(), List.of(), false));
    ResolvedStatement resolved = ResolvedStatement.analyze(statement);

    assertSame(statement, StandardSqlLowering.of(DialectCapabilities.none()).lower(resolved));
  }

  @Test
  void rendererRejectsInputPreparedForAnotherDialect() {
    PetTable pet = new PetTable(PET);
    SelectStatement statement = new SelectStatement(List.of(pet.id()), pet);
    TrackingDialect first = new TrackingDialect("first-target", false, false);
    TrackingDialect second = new TrackingDialect("second-target", false, false);
    RenderInput input = RenderInput.prepare(ResolvedStatement.analyze(statement), first);

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> second.renderer().render(input));

    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    assertEquals("first-target", failure.diagnostics().dialect().dialectId());
    assertTrue(failure.getMessage().contains("second-target"));
  }

  @Test
  void portableNormalizationRemovesEmptyMembershipAndItsPrunedSlot() {
    PetTable pet = new PetTable(PET);
    ParameterSlot<Long> pruned = new ParameterSlot<>(0, Long.class, false);
    SelectStatement statement =
        new SelectStatement(List.of(pet.id()), pet, new InPredicate<>(pruned, List.of(), false));

    SqlTemplate template =
        SqlCompiler.compile(statement, new TrackingDialect("empty-membership-test", false, false));

    assertEquals("SELECT \"pet\".\"id\" FROM \"pet\" WHERE FALSE", template.sql());
    assertTrue(template.parameterLayout().isEmpty());
  }

  @Test
  void finalLayoutUsesLoweredPredicateOrderAndOriginalLogicalOrdinals() {
    PetTable pet = new PetTable(PET);
    ParameterSlot<String> name = new ParameterSlot<>(0, String.class, false);
    ParameterSlot<Long> id = new ParameterSlot<>(1, Long.class, false);
    SelectStatement statement =
        new SelectStatement(
            List.of(pet.id()),
            pet,
            LogicalPredicate.and(List.of(pet.name().eq(name), pet.id().ge(id))));
    TrackingDialect dialect =
        new TrackingDialect(
            "order-swap-test",
            false,
            false,
            input -> {
              SelectStatement select = (SelectStatement) input.statement();
              LogicalPredicate logical = (LogicalPredicate) select.where().orElseThrow();
              return new SelectStatement(
                  select.selections(),
                  select.fromClause(),
                  new LogicalPredicate(
                      LogicalOperator.AND,
                      List.of(logical.operands().get(1), logical.operands().get(0))));
            });

    SqlTemplate template = SqlCompiler.compile(statement, dialect);

    assertEquals(
        "SELECT \"pet\".\"id\" FROM \"pet\" WHERE \"pet\".\"id\" >= ? AND "
            + "\"pet\".\"pet_name\" = ?",
        template.sql());
    assertEquals(
        List.of(1, 0),
        template.parameterLayout().slots().stream().map(slot -> slot.ordinal()).toList());
  }

  @Test
  void nullOrderingAndDistinctCountBehaviorsHaveVisibleFinalPositions() {
    PetTable pet = new PetTable(PET);
    ParameterSlot<String> ordered = new ParameterSlot<>(0, String.class, true);
    SelectStatement orderedStatement =
        new SelectStatement(
            false,
            List.of(pet.id()),
            List.of(),
            pet,
            null,
            List.of(new OrderByItem(ordered, OrderDirection.ASC, NullOrder.LAST)),
            null);

    SqlTemplate orderedTemplate =
        SqlCompiler.compile(orderedStatement, new TrackingDialect("null-order-test", false, false));

    assertEquals(
        "SELECT \"pet\".\"id\" FROM \"pet\" "
            + "ORDER BY CASE WHEN ? IS NULL THEN 1 ELSE 0 END ASC, ? ASC",
        orderedTemplate.sql());
    assertEquals(2, orderedTemplate.parameterCount());
    assertEquals(
        List.of(0, 0),
        orderedTemplate.parameterLayout().slots().stream().map(slot -> slot.ordinal()).toList());
  }

  private static SelectStatement distinctOrderBy(
      PetTable pet, SelectedOutputReference<String> reference) {
    return new SelectStatement(
        true,
        List.of(pet.name()),
        List.of(),
        pet,
        null,
        List.of(new OrderByItem(reference, OrderDirection.ASC, NullOrder.DIALECT_DEFAULT)),
        null);
  }

  private record Pet(Long id, String name) {}

  private static final class PetTable extends TableExpression<Pet> {

    private final ColumnExpression<Pet, Long> id = column(ID);
    private final ColumnExpression<Pet, String> name = column(NAME);

    private PetTable(EntityMeta<Pet> entity) {
      super(entity);
    }

    private PetTable(EntityMeta<Pet> entity, Identifier alias) {
      super(entity, alias);
    }

    private ColumnExpression<Pet, Long> id() {
      return id;
    }

    private ColumnExpression<Pet, String> name() {
      return name;
    }

    @Override
    public PetTable as(Identifier alias) {
      return new PetTable(entity(), alias);
    }
  }

  private static final class TrackingDialect implements Dialect {

    private final String id;
    private final boolean stable;
    private final boolean nativeNullOrdering;
    private final DialectLowering lowering;
    private final DialectCapabilities capabilities;
    private final SqlRenderer renderer;
    private final List<String> events = new ArrayList<>();

    private TrackingDialect(String id, boolean stable, boolean nativeNullOrdering) {
      this(id, stable, nativeNullOrdering, DialectLowering.identity());
    }

    private TrackingDialect(
        String id, boolean stable, boolean nativeNullOrdering, DialectLowering lowering) {
      this.id = id;
      this.stable = stable;
      this.nativeNullOrdering = nativeNullOrdering;
      this.lowering = lowering;
      java.util.EnumSet<DialectFeature> features =
          java.util.EnumSet.of(
              DialectFeature.SCHEMA_QUALIFIED_TABLES,
              DialectFeature.PARAMETERIZED_LIMIT,
              DialectFeature.PARAMETERIZED_OFFSET,
              DialectFeature.COUNT_AGGREGATE,
              DialectFeature.COUNT_DISTINCT,
              DialectFeature.IN_SUBQUERY,
              DialectFeature.EXISTS_SUBQUERY,
              DialectFeature.SCALAR_SUBQUERY,
              DialectFeature.CORRELATED_SUBQUERY,
              DialectFeature.INNER_JOIN,
              DialectFeature.LEFT_JOIN,
              DialectFeature.RIGHT_JOIN,
              DialectFeature.FULL_JOIN,
              DialectFeature.CROSS_JOIN,
              DialectFeature.DERIVED_TABLE);
      if (nativeNullOrdering) {
        features.add(DialectFeature.NULLS_FIRST_LAST);
      }
      this.capabilities = DialectCapabilities.of(features.toArray(DialectFeature[]::new));
      this.renderer = new StandardSqlRenderer(this);
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public IdentifierRules identifierRules() {
      return StandardIdentifierRules.INSTANCE;
    }

    @Override
    public DialectCapabilities capabilities() {
      return capabilities;
    }

    @Override
    public boolean hasStablePlanCacheIdentity() {
      return stable;
    }

    @Override
    public DialectLowering lowering() {
      return input -> {
        events.add("lower");
        return lowering.lower(input);
      };
    }

    @Override
    public SqlRenderer renderer() {
      return input -> {
        events.add("render");
        return renderer.render(input);
      };
    }

    @Override
    public void validate(ResolvedStatement resolved) {
      events.add("validate");
      Dialect.super.validate(resolved);
    }
  }
}
