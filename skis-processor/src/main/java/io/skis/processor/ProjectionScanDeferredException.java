package io.skis.processor;

/** Signals that a projection must be scanned again after another processing round. */
final class ProjectionScanDeferredException extends Exception {

  ProjectionScanDeferredException(String message) {
    super(message);
  }
}
