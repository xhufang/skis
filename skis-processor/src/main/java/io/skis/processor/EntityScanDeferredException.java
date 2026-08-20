package io.skis.processor;

/** Signals that an entity must be scanned again after another annotation-processing round. */
final class EntityScanDeferredException extends Exception {

  EntityScanDeferredException(String message) {
    super(message);
  }
}
