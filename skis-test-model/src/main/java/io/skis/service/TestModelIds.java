package io.skis.service;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local identifier sequence used only by the unpublished shared test model. */
final class TestModelIds {

  private static final AtomicLong NEXT = new AtomicLong(System.currentTimeMillis());

  private TestModelIds() {}

  static long next() {
    return NEXT.incrementAndGet();
  }
}
