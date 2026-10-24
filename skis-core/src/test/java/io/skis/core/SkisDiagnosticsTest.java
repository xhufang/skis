package io.skis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies the T06 code table, immutable diagnostics, and constrained exception construction. */
class SkisDiagnosticsTest {

  @Test
  void exposesAUniqueExplicitCodeForEveryFamily() {
    Set<String> codes = new HashSet<>();
    for (SkisErrorCode code : SkisErrorCode.values()) {
      assertTrue(code.code().matches("[A-Z]{4,5}[0-9]{3}"), code::code);
      assertTrue(code.code().startsWith(code.family().prefix()), code::code);
      assertTrue(codes.add(code.code()), () -> "duplicate code " + code.code());
      assertFalse(code.description().isBlank());
      if (code.isDatabaseCategory()) {
        assertEquals(SkisErrorFamily.EXECUTION, code.family());
        assertEquals(code.name().replace("DB_", ""), code.databaseCategory(), code::name);
      } else {
        assertNull(code.databaseCategory());
      }
    }
  }

  @Test
  void everyDatabaseCategoryHasOneFixedCode() {
    SqlExceptionCategoryCodes.verify();
  }

  @Test
  void diagnosticsKeepOnlyPresentGroupsAndNeverRenderACause() {
    SkisDiagnostics diagnostics =
        SkisDiagnostics.builder(SkisDiagnosticStage.EXECUTION)
            .operation(SkisOperation.QUERY)
            .phase("execution")
            .statementKind(SkisStatementKind.SELECT)
            .sqlFingerprint("abc123")
            .dialectId("postgresql")
            .driver("23505", 17)
            .databaseCategory("DUPLICATE_KEY")
            .types("java.lang.Long", "java.lang.String")
            .observedCount(2)
            .detail("structural detail")
            .build();

    assertEquals(SkisDiagnosticStage.EXECUTION, diagnostics.stage());
    assertEquals(SkisOperation.QUERY, diagnostics.execution().operation());
    assertEquals("execution", diagnostics.execution().phase());
    assertEquals("postgresql", diagnostics.dialect().dialectId());
    assertEquals("23505", diagnostics.driver().sqlState());
    assertEquals(Integer.valueOf(17), diagnostics.driver().vendorCode());
    assertEquals(2, diagnostics.facts().observedCount());
    assertFalse(diagnostics.safeText().contains("SQL"));
    assertFalse(diagnostics.safeText().contains("Throwable"));
    assertTrue(diagnostics.safeText().contains("detail=structural detail"));
  }

  @Test
  void absentInformationStaysAbsent() {
    SkisDiagnostics diagnostics = SkisDiagnostics.of(SkisDiagnosticStage.QUERY_CONSTRUCTION);

    assertNull(diagnostics.query());
    assertNull(diagnostics.querySemantics());
    assertNull(diagnostics.dialect());
    assertNull(diagnostics.execution());
    assertNull(diagnostics.driver());
    assertNull(diagnostics.facts());
    assertEquals("stage=query-construction", diagnostics.safeText());
  }

  @Test
  void zeroVendorCodeStaysAbsent() {
    SkisDiagnostics diagnostics =
        SkisDiagnostics.builder(SkisDiagnosticStage.EXECUTION).driver(null, 0).build();

    assertNull(diagnostics.driver());
    assertFalse(diagnostics.safeText().contains("vendorCode=0"));
  }

  @Test
  void exceptionMessageIsASafeSnapshotAndKeepsTheOriginalCause() {
    RuntimeException cause = new RuntimeException("sensitive driver value");
    QueryValidationException failure =
        QueryValidationException.of(
            SkisErrorCode.QUERY_TYPE_MISMATCH, "declared type mismatch", cause);

    assertEquals(SkisErrorCode.QUERY_TYPE_MISMATCH, failure.code());
    assertEquals("SKISQ008", failure.errorCode());
    assertSame(cause, failure.getCause());
    assertFalse(failure.getMessage().contains("sensitive driver value"));
    assertTrue(failure.getMessage().contains("SKISQ008"));
    assertTrue(failure.getMessage().contains("declared type mismatch"));
  }

  @Test
  void frameworkCauseMessagesNeverBecomeSafeDiagnosticDetail() {
    IllegalArgumentException cause =
        new IllegalArgumentException("sensitive lower-layer value and complete SQL");

    QueryValidationException failure =
        QueryValidationException.ofCause(
            SkisErrorCode.QUERY_DERIVED_SHAPE, cause, "derived query validation failed");

    assertSame(cause, failure.getCause());
    assertTrue(failure.getMessage().contains("derived query validation failed"));
    assertFalse(failure.getMessage().contains("sensitive lower-layer value"));
    assertFalse(failure.diagnostics().safeText().contains("complete SQL"));
  }

  @Test
  void rejectsAnErrorCodeFromAnotherExceptionFamily() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            QueryValidationException.of(
                SkisErrorCode.DB_DUPLICATE_KEY, "connection failure cannot be validation"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SkisConfigurationException.of(
                SkisErrorCode.QUERY_WHERE_CONTRACT, "query failure cannot be assembly"));
  }

  @Test
  void nonUniqueResultRecordsOnlyTheObservedLowerBound() {
    NonUniqueResultException failure = NonUniqueResultException.of("more than one row");

    assertEquals(SkisErrorCode.QUERY_MULTIPLE_ROWS, failure.code());
    assertEquals(2, failure.diagnostics().facts().observedCount());
    assertEquals("more than one row", failure.diagnostics().facts().detail());
  }

  @Test
  void explicitDiagnosticsMustMatchTheExceptionContract() {
    SkisDiagnostics wrongQueryStage =
        SkisDiagnostics.of(SkisDiagnosticStage.EXECUTION, "invalid WHERE");
    SkisDiagnostics missingQueryDetail =
        SkisDiagnostics.of(SkisErrorCode.QUERY_WHERE_CONTRACT.defaultStage());
    SkisDiagnostics missingObservedCount =
        SkisDiagnostics.of(SkisErrorCode.QUERY_MULTIPLE_ROWS.defaultStage(), "more than one row");

    assertThrows(
        IllegalArgumentException.class,
        () -> QueryValidationException.of(SkisErrorCode.QUERY_WHERE_CONTRACT, wrongQueryStage));
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryValidationException.of(SkisErrorCode.QUERY_WHERE_CONTRACT, missingQueryDetail));
    assertThrows(
        IllegalArgumentException.class, () -> NonUniqueResultException.of(missingObservedCount));
  }

  @Test
  void detailIsAdditiveWithoutLosingExistingGroups() {
    SkisDiagnostics base =
        SkisDiagnostics.builder(SkisDiagnosticStage.RESULT_DECODING).observedCount(2).build();
    SkisDiagnostics detailed = base.withDetail("query expected at most one row");

    assertEquals(2, detailed.facts().observedCount());
    assertEquals("query expected at most one row", detailed.facts().detail());
    assertEquals(SkisDiagnosticStage.RESULT_DECODING, detailed.stage());
  }

  @Test
  void semanticGroupsRemainImmutableAndSurviveAdditionalDetail() {
    SkisDiagnostics.QueryLocation dependency =
        new SkisDiagnostics.QueryLocation("$", null, 1, null, null);
    SkisDiagnostics.QuerySemantics semantics =
        new SkisDiagnostics.QuerySemantics(
            SkisDiagnostics.QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            null,
            null,
            dependency,
            2,
            null);
    SkisDiagnostics base = semanticDiagnostics(semantics);
    SkisDiagnostics detailed = base.withDetail("additional structural context");

    assertEquals(semantics, detailed.querySemantics());
    assertEquals(base.query(), detailed.query());
    assertEquals(base, semanticDiagnostics(semantics));
    assertEquals(base.hashCode(), semanticDiagnostics(semantics).hashCode());
    assertNotEquals(
        base,
        semanticDiagnostics(
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.DUPLICATE_GROUP_KEY,
                null,
                null,
                null,
                null,
                null)));
    assertTrue(detailed.safeText().contains("semanticReason=NON_GROUPED_DEPENDENCY"));
    assertTrue(detailed.safeText().contains("missingDependencySource=1"));
    assertTrue(detailed.safeText().contains("missingPropertyOrdinal=2"));
    assertFalse(detailed.safeText().contains("missingOutputOrdinal"));
  }

  @Test
  void semanticDependenciesRequireExactlyOneTypedColumnOrdinal() {
    SkisDiagnostics.QueryLocation dependency =
        new SkisDiagnostics.QueryLocation("$", null, 0, null, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.NON_GROUPED_DEPENDENCY,
                null,
                null,
                dependency,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.NON_GROUPED_DEPENDENCY,
                null,
                null,
                dependency,
                0,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.NON_GROUPED_DEPENDENCY,
                null,
                null,
                dependency,
                -1,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.NON_GROUPED_DEPENDENCY,
                null,
                null,
                new SkisDiagnostics.QueryLocation("$", null, null, null, null),
                0,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.DUPLICATE_GROUP_KEY,
                null,
                null,
                dependency,
                0,
                null));
    SkisDiagnostics.QuerySemantics derived =
        new SkisDiagnostics.QuerySemantics(
            SkisDiagnostics.QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            null,
            null,
            dependency,
            null,
            3);
    assertEquals(3, derived.missingOutputOrdinal());
  }

  @Test
  void aggregateFactsAcceptOnlyFrameworkKindsAndAnActualWritingBlock() {
    for (String kind : new String[] {"COUNT", "SUM", "AVG", "MIN", "MAX"}) {
      SkisDiagnostics.QuerySemantics semantics =
          new SkisDiagnostics.QuerySemantics(
              SkisDiagnostics.QuerySemanticReason.OUTER_ONLY_AGGREGATE,
              kind,
              "$/SELECT[0]#0",
              null,
              null,
              null);
      QueryValidationException failure =
          QueryValidationException.of(
              SkisErrorCode.QUERY_AGGREGATE_CONTRACT, semanticDiagnostics(semantics));
      assertEquals(kind, failure.diagnostics().querySemantics().aggregateKind());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.AGGREGATE_IN_CLAUSE,
                "COUNT(sensitive-value)",
                "$",
                null,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.NESTED_AGGREGATE,
                "COUNT",
                null,
                null,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkisDiagnostics.QuerySemantics(
                SkisDiagnostics.QuerySemanticReason.INVALID_GROUP_KEY,
                "COUNT",
                "$",
                null,
                null,
                null));
  }

  @Test
  void everySemanticReasonRequiresItsMatchingCodeAndFactShape() {
    SkisDiagnostics.QueryLocation dependency =
        new SkisDiagnostics.QueryLocation("$", null, 0, null, null);
    for (SkisDiagnostics.QuerySemantics semantics :
        new SkisDiagnostics.QuerySemantics[] {
          new SkisDiagnostics.QuerySemantics(
              SkisDiagnostics.QuerySemanticReason.NON_GROUPED_DEPENDENCY,
              null, null, dependency, 0, null),
          new SkisDiagnostics.QuerySemantics(
              SkisDiagnostics.QuerySemanticReason.DUPLICATE_GROUP_KEY,
              null, null, null, null, null),
          new SkisDiagnostics.QuerySemantics(
              SkisDiagnostics.QuerySemanticReason.INVALID_GROUP_KEY,
              null, null, null, null, null)
        }) {
      assertSame(
          semantics,
          QueryValidationException.of(
                  SkisErrorCode.QUERY_GROUPING_CONTRACT, semanticDiagnostics(semantics))
              .diagnostics()
              .querySemantics());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              QueryValidationException.of(
                  SkisErrorCode.QUERY_AGGREGATE_CONTRACT, semanticDiagnostics(semantics)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SkisDiagnostics.QuerySemantics(
                  semantics.reason(), "COUNT", "$", null, null, null));
    }
    for (SkisDiagnostics.QuerySemanticReason reason :
        new SkisDiagnostics.QuerySemanticReason[] {
          SkisDiagnostics.QuerySemanticReason.AGGREGATE_IN_CLAUSE,
          SkisDiagnostics.QuerySemanticReason.NESTED_AGGREGATE,
          SkisDiagnostics.QuerySemanticReason.OUTER_ONLY_AGGREGATE
        }) {
      SkisDiagnostics.QuerySemantics semantics =
          new SkisDiagnostics.QuerySemantics(reason, "COUNT", "$", null, null, null);
      assertSame(
          semantics,
          QueryValidationException.of(
                  SkisErrorCode.QUERY_AGGREGATE_CONTRACT, semanticDiagnostics(semantics))
              .diagnostics()
              .querySemantics());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              QueryValidationException.of(
                  SkisErrorCode.QUERY_GROUPING_CONTRACT, semanticDiagnostics(semantics)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new SkisDiagnostics.QuerySemantics(reason, null, null, null, null, null));
    }
  }

  @Test
  void groupingAndAggregateCodesRequireMatchingReasonsAndDetectionLocations() {
    SkisDiagnostics.QuerySemantics grouping =
        new SkisDiagnostics.QuerySemantics(
            SkisDiagnostics.QuerySemanticReason.DUPLICATE_GROUP_KEY,
            null,
            null,
            null,
            null,
            null);
    SkisDiagnostics.QuerySemantics aggregate =
        new SkisDiagnostics.QuerySemantics(
            SkisDiagnostics.QuerySemanticReason.NESTED_AGGREGATE,
            "COUNT",
            "$",
            null,
            null,
            null);
    assertEquals(
        "SKISQ031",
        QueryValidationException.of(
                SkisErrorCode.QUERY_GROUPING_CONTRACT, semanticDiagnostics(grouping))
            .errorCode());
    assertEquals(
        "SKISQ032",
        QueryValidationException.of(
                SkisErrorCode.QUERY_AGGREGATE_CONTRACT, semanticDiagnostics(aggregate))
            .errorCode());
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryValidationException.of(SkisErrorCode.QUERY_GROUPING_CONTRACT, "missing facts"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            QueryValidationException.of(
                SkisErrorCode.QUERY_GROUPING_CONTRACT, semanticDiagnostics(aggregate)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            QueryValidationException.of(
                SkisErrorCode.QUERY_AGGREGATE_CONTRACT, semanticDiagnostics(grouping)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            QueryValidationException.of(
                SkisErrorCode.QUERY_TYPE_MISMATCH, semanticDiagnostics(grouping)));
    SkisDiagnostics incomplete =
        SkisDiagnostics.builder(SkisDiagnosticStage.SEMANTIC_VALIDATION)
            .queryBlock("$")
            .clause("SELECT")
            .querySemantics(grouping)
            .detail("missing expression")
            .build();
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryValidationException.of(SkisErrorCode.QUERY_GROUPING_CONTRACT, incomplete));
  }

  private static SkisDiagnostics semanticDiagnostics(SkisDiagnostics.QuerySemantics semantics) {
    return SkisDiagnostics.builder(SkisDiagnosticStage.SEMANTIC_VALIDATION)
        .queryBlock("$")
        .clause("SELECT")
        .expressionPath("$ SELECT item #0")
        .querySemantics(semantics)
        .detail("grouped expression is invalid")
        .build();
  }

  private static final class SqlExceptionCategoryCodes {

    private static void verify() {
      // The fixed mapping is owned by the dialect SPI; this guard keeps the core table deliberate.
      assertEquals("DUPLICATE_KEY", SkisErrorCode.DB_DUPLICATE_KEY.databaseCategory());
      assertEquals(
          "FOREIGN_KEY_VIOLATION", SkisErrorCode.DB_FOREIGN_KEY_VIOLATION.databaseCategory());
      assertEquals(
          "CONSTRAINT_VIOLATION", SkisErrorCode.DB_CONSTRAINT_VIOLATION.databaseCategory());
      assertEquals("CONNECTION_FAILURE", SkisErrorCode.DB_CONNECTION_FAILURE.databaseCategory());
      assertEquals("TIMEOUT", SkisErrorCode.DB_TIMEOUT.databaseCategory());
      assertEquals("QUERY_CANCELED", SkisErrorCode.DB_QUERY_CANCELED.databaseCategory());
      assertEquals("LOCK_NOT_AVAILABLE", SkisErrorCode.DB_LOCK_NOT_AVAILABLE.databaseCategory());
      assertEquals("DEADLOCK", SkisErrorCode.DB_DEADLOCK.databaseCategory());
      assertEquals(
          "SERIALIZATION_FAILURE", SkisErrorCode.DB_SERIALIZATION_FAILURE.databaseCategory());
      assertEquals("UNCATEGORIZED", SkisErrorCode.DB_UNCATEGORIZED.databaseCategory());
    }
  }
}
