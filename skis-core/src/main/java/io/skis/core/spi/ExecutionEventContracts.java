package io.skis.core.spi;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Package-private constructor checks shared by the five public event records. */
final class ExecutionEventContracts {

  private ExecutionEventContracts() {}

  static void positive(long value, String name) {
    if (value <= 0L) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  static void nonNegative(long value) {
    if (value < 0L) {
      throw new IllegalArgumentException("durationNanos must not be negative");
    }
  }

  static void unknownOrNonNegative(long value, long unknown, String name) {
    if (value != unknown && value < 0L) {
      throw new IllegalArgumentException(name + " must be non-negative or the unknown sentinel");
    }
  }

  static void text(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  static void optionalText(@Nullable String value, String name) {
    if (value != null && value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  static void completion(
      ExecutionOutcome outcome,
      @Nullable SkisDiagnosticStage failureStage,
      @Nullable SkisDiagnostics diagnostics) {
    Objects.requireNonNull(outcome, "outcome");
    if (outcome == ExecutionOutcome.SUCCEEDED) {
      if (failureStage != null || diagnostics != null) {
        throw new IllegalArgumentException(
            "successful events must not contain failureStage or diagnostics");
      }
      return;
    }
    if (failureStage == null) {
      throw new IllegalArgumentException("failed events require failureStage");
    }
    if (diagnostics != null && diagnostics.stage() != failureStage) {
      throw new IllegalArgumentException("diagnostics stage must match failureStage");
    }
  }

  static void termination(
      ExecutionOperationKind operationKind,
      ExecutionOutcome outcome,
      ExecutionTermination termination) {
    Objects.requireNonNull(operationKind, "operationKind");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(termination, "termination");
    if ((outcome == ExecutionOutcome.FAILED) != (termination == ExecutionTermination.FAILED)) {
      throw new IllegalArgumentException("FAILED termination must exactly match a failed outcome");
    }
    boolean resourceOperation =
        operationKind == ExecutionOperationKind.CURSOR
            || operationKind == ExecutionOperationKind.STREAM;
    if (resourceOperation && termination == ExecutionTermination.COMPLETED) {
      throw new IllegalArgumentException(
          "successful cursor and stream operations must terminate as EXHAUSTED or CLOSED_EARLY");
    }
    if (!resourceOperation
        && (termination == ExecutionTermination.EXHAUSTED
            || termination == ExecutionTermination.CLOSED_EARLY)) {
      throw new IllegalArgumentException(
          "EXHAUSTED and CLOSED_EARLY are only valid for cursor or stream operations");
    }
  }
}
