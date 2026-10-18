package io.skis.dialect;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import org.junit.jupiter.api.Test;

class SqlRenderExceptionTest {

  @Test
  void explicitDiagnosticsRequireTheCodeStageDialectAndDetail() {
    SkisDiagnostics wrongStage =
        SkisDiagnostics.builder(SkisDiagnosticStage.EXECUTION)
            .dialectId("test")
            .detail("rendering failed")
            .build();
    SkisDiagnostics missingDialect =
        SkisDiagnostics.of(SkisErrorCode.DIALECT_RENDER_FAILURE.defaultStage(), "rendering failed");
    SkisDiagnostics missingDetail =
        SkisDiagnostics.builder(SkisErrorCode.DIALECT_RENDER_FAILURE.defaultStage())
            .dialectId("test")
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () -> SqlRenderException.of(SkisErrorCode.DIALECT_RENDER_FAILURE, wrongStage));
    assertThrows(
        IllegalArgumentException.class,
        () -> SqlRenderException.of(SkisErrorCode.DIALECT_RENDER_FAILURE, missingDialect));
    assertThrows(
        IllegalArgumentException.class,
        () -> SqlRenderException.of(SkisErrorCode.DIALECT_RENDER_FAILURE, missingDetail));
  }
}
