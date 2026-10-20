package io.skis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
