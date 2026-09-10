package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class QueryParametersTest {

  @Test
  void createsOpaqueIdentityReferencesWithoutValuesOrOrdinals() {
    QueryParameter<Long> first = Sql.parameter(Long.class, "ownerId");
    QueryParameter<Long> second = Sql.parameter(Long.class, "ownerId");
    QueryParameter<Long> primitive = Sql.parameter(long.class);

    assertNotEquals(first, second);
    assertEquals(Long.class, first.javaType());
    assertEquals(Long.class, primitive.javaType());
    assertEquals("ownerId", first.diagnosticName().orElseThrow());
    assertTrue(second.toString().contains("ownerId"));
  }

  @Test
  void capturesMutableValuesExactlyOnceAtTheBindingBoundary() {
    QueryParameter<byte[]> bytes = Sql.parameter(byte[].class, "bytes");
    QueryParameter<Timestamp> timestamp = Sql.parameter(Timestamp.class, "createdAt");
    byte[] sourceBytes = {1, 2, 3};
    Timestamp sourceTimestamp = Timestamp.valueOf("2026-09-10 10:11:12.123456789");
    Timestamp expectedTimestamp = (Timestamp) sourceTimestamp.clone();

    QueryParameters parameters =
        QueryParameters.builder()
            .bind(bytes, sourceBytes)
            .bind(timestamp, sourceTimestamp)
            .build();
    sourceBytes[0] = 9;
    sourceTimestamp.setTime(0);
    sourceTimestamp.setNanos(7);

    List<@Nullable Object> firstRead = parameters.valuesFor(List.of(bytes, timestamp));
    List<@Nullable Object> secondRead = parameters.valuesFor(List.of(bytes, timestamp));
    List<@Nullable Object> repeatedReference =
        parameters.valuesFor(List.of(bytes, timestamp, bytes));
    assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) firstRead.get(0));
    assertEquals(expectedTimestamp, firstRead.get(1));
    assertSame(firstRead.get(0), secondRead.get(0));
    assertSame(firstRead.get(1), secondRead.get(1));
    assertSame(repeatedReference.get(0), repeatedReference.get(2));
  }

  @Test
  void validatesDuplicateMissingExtraAndRuntimeJavaTypeBindings() {
    QueryParameter<Long> used = Sql.parameter(Long.class, "used");
    QueryParameter<Long> extra = Sql.parameter(Long.class, "extra");
    QueryParameter<Long> required = QueryParameter.anonymousNonNull(Long.class);
    QueryParameters.Builder duplicate = QueryParameters.builder().bind(used, 1L);

    assertThrows(QueryValidationException.class, () -> duplicate.bind(used, 2L));
    assertThrows(QueryValidationException.class, () -> QueryParameters.of(required, null));
    assertThrows(
        QueryValidationException.class,
        () -> QueryParameters.empty().valuesFor(List.of(used)));
    assertThrows(
        QueryValidationException.class,
        () ->
            QueryParameters.builder()
                .bind(used, 1L)
                .bind(extra, 2L)
                .build()
                .validateFor(List.of(used)));
    assertThrows(QueryValidationException.class, () -> bindWrongRuntimeType(used, "wrong"));
  }

  @Test
  void validatesTheDescriptionBeforeProjectingValuesForOneFinalStatement() {
    QueryParameter<Long> retained = Sql.parameter(Long.class, "retained");
    QueryParameter<Long> removed = Sql.parameter(Long.class, "removed");
    QueryParameters parameters =
        QueryParameters.builder().bind(retained, 1L).bind(removed, 2L).build();

    parameters.validateFor(List.of(retained, removed));

    assertEquals(List.of(1L), parameters.valuesFor(List.of(retained)));
  }

  @Test
  void immutableBindReturnsAnIndependentEnvironmentAndSupportsExplicitNull() {
    QueryParameter<String> parameter = Sql.parameter(String.class, "optionalName");
    QueryParameters empty = QueryParameters.empty();
    QueryParameters bound = empty.bind(parameter, null);

    assertTrue(empty.isEmpty());
    assertEquals(1, bound.size());
    assertNull(bound.valuesFor(List.of(parameter)).getFirst());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void bindWrongRuntimeType(QueryParameter<?> parameter, Object value) {
    QueryParameters.of((QueryParameter) parameter, value);
  }
}
