package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WrittenTest {
  @Test
  void presentResultsExposeOnlyDetachedMemoryValues() {
    var result = new DefaultWritten<>(2, List.of("first", "second"));
    assertEquals(2, result.affectedRows());
    assertTrue(result.hasRow());
    assertTrue(result.isPresent());
    assertFalse(result.isEmpty());
    assertSame(result.get(), result.orElseThrow());
    assertSame(result.get(), result.orElse(null));
    assertSame(result.get(), result.orElseGet(() -> fail("eager default")));
    assertSame(result.get(), result.orElseThrow(() -> new AssertionError("eager exception")));
    assertEquals(Optional.of(2), result.map(List::size));
    assertEquals(Optional.empty(), result.map(value -> null));
    assertEquals(Optional.of("first"), result.flatMap(value -> Optional.of(value.getFirst())));
    assertEquals(result.toOptional(), result.filter(value -> value.size() == 2));
    assertEquals(Optional.empty(), result.filter(value -> false));
    assertEquals(result.toOptional(), result.or(() -> fail("eager alternative")));
    assertEquals(1L, result.stream().count(), "the list is one in-memory carrier");
    var calls = new AtomicInteger();
    result.ifPresent(value -> calls.incrementAndGet());
    result.ifPresentOrElse(value -> calls.incrementAndGet(), () -> fail("wrong branch"));
    assertEquals(2, calls.get());
    assertEquals(new DefaultWritten<>(2, result.get()), result);
    assertEquals(new DefaultWritten<>(2, result.get()).hashCode(), result.hashCode());
    assertNotEquals(new DefaultWritten<>(1, result.get()), result);
    assertThrows(UnsupportedOperationException.class, () -> result.get().add("third"));
  }

  @Test
  void emptyResultsPreserveCountsAndOptionalLazinessAndExceptions() {
    var result = new DefaultWritten<String>(0, null);
    assertEquals(0, result.affectedRows());
    assertTrue(result.isEmpty());
    assertFalse(result.hasRow());
    assertThrows(NoSuchElementException.class, result::get);
    assertThrows(NoSuchElementException.class, result::orElseThrow);
    var failure = new IllegalStateException("business callback");
    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> result.orElseThrow(() -> failure)));
    assertNull(result.orElse(null));
    assertNull(result.orElseGet(() -> null));
    assertEquals("default", result.orElse("default"));
    assertEquals("default", result.orElseGet(() -> "default"));
    result.ifPresent(value -> fail("wrong branch"));
    var calls = new AtomicInteger();
    result.ifPresentOrElse(value -> fail("wrong branch"), calls::incrementAndGet);
    assertEquals(1, calls.get());
    assertTrue(result.toOptional().isEmpty());
    assertTrue(result.map(value -> fail("eager mapper")).isEmpty());
    assertTrue(result.flatMap(value -> fail("eager mapper")).isEmpty());
    assertTrue(result.filter(value -> fail("eager predicate")).isEmpty());
    assertEquals(Optional.of("other"), result.or(() -> Optional.of("other")));
    assertEquals(0L, result.stream().count());
    assertThrows(NullPointerException.class, () -> result.map(null));
    assertThrows(NullPointerException.class, () -> result.flatMap(null));
    assertThrows(NullPointerException.class, () -> result.filter(null));
    assertThrows(NullPointerException.class, () -> result.or(() -> null));
    assertThrows(IllegalArgumentException.class, () -> new DefaultWritten<>(-1, "bad"));
    assertNotEquals(result, new DefaultWritten<>(1, null));
  }

  @Test
  void callbacksPropagateAndFormattingNeverCallsBusinessToString() {
    Object value = new Object() {
      @Override public String toString() { throw new AssertionError("sensitive value"); }
    };
    var result = new DefaultWritten<>(1, value);
    assertEquals("Written[values redacted]", result.toString());
    var failure = new IllegalArgumentException("callback");
    assertSame(failure, assertThrows(IllegalArgumentException.class,
        () -> result.ifPresent(ignored -> { throw failure; })));
    assertThrows(NullPointerException.class, () -> result.flatMap(ignored -> null));
  }
}
