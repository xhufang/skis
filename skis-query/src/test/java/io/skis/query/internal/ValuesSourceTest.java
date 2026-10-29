package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.QueryValidationException;
import io.skis.query.*;
import io.skis.sql.ast.DerivedColumnExpression;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ValuesSourceTest {
  @Test void capturesMutableInputsAndSeparatesSourceOccurrencesAndHandleIdentity() {
    byte[] value = {1, 2};
    var bytes = Sql.output(Sql.value(value), "bytes");
    var optional = Sql.outputNullable(Sql.value("template"), "optional");
    var source = Sql.values(row -> row.value(bytes, value).value(optional, null)).as("incoming");
    value[0] = 99;
    var owned = ValuesSources.require(source);
    assertArrayEquals(new byte[] {1, 2}, (byte[]) owned.values[0]);
    assertNull(owned.values[1]);
    assertInstanceOf(NonNullSelectable.class, source.column(bytes));
    assertFalse(source.column(optional) instanceof NonNullSelectable<?>);
    assertTrue(((ExpressionSelectable<?>) source.column(bytes)).parameterReferences().isEmpty());
    assertTrue(((ExpressionSelectable<?>) source.column(optional)).parameterReferences().isEmpty(),
        "Sql.output templates carry Codec shape, never their own captured parameters into MERGE");
    var first = (DerivedColumnExpression<?>) SelectableSupport.expression(source.column(bytes));
    var second = (DerivedColumnExpression<?>) SelectableSupport.expression(source.as("incoming").column(bytes));
    assertNotSame(first.relation(), second.relation());
    assertEquals(first.relation(), second.relation(), "structure equality never substitutes for scope identity");
    var foreign = Sql.output(Sql.value(value), "bytes");
    assertThrows(QueryValidationException.class, () -> source.column(foreign));
    assertFalse(source.toString().contains("template"));
  }

  @Test void rejectsDuplicateNamesNullNonNullInputsAndNonPhysicalExcluded() {
    var name = Sql.output(Sql.value("one"), "name");
    var duplicate = Sql.output(Sql.value("two"), "name");
    assertThrows(QueryValidationException.class, () -> Sql.values(row -> row
        .value((DerivedOutput<String>) name, null)));
    assertThrows(QueryValidationException.class, () -> Sql.values(row -> row
        .value(name, "one")
        .value(duplicate, "two")));
    assertThrows(QueryValidationException.class, () -> Sql.excluded(Sql.value("one")));
  }

  @Test void supportsWideRowsAndClosesCollectorsOnSuccessAndFailure() {
    var handles = new ArrayList<NonNullDerivedOutput<Long>>();
    for (int i = 0; i < 12; i++) handles.add(Sql.output(Sql.value(0L), "c" + i));
    var retained = new AtomicReference<ValuesRow>();
    var source = Sql.values(row -> {
      retained.set(row);
      for (int i = 0; i < handles.size(); i++) row.value(handles.get(i), (long) i);
    });
    var owned = ValuesSources.require(source);
    assertEquals(12, owned.outputs.size());
    for (int i = 0; i < handles.size(); i++) assertEquals((long) i, owned.values[i]);
    assertThrows(QueryValidationException.class, () -> Objects.requireNonNull(retained.get()).value(handles.getFirst(), 99L));
    assertThrows(QueryValidationException.class, () -> Sql.values(row -> {}));
    assertThrows(QueryValidationException.class,
        () -> Sql.values(row -> row.value(handles.getFirst(), 1L).value(handles.getFirst(), 2L)));
    var failure = new IllegalStateException("callback failure");
    assertSame(failure, assertThrows(IllegalStateException.class, () -> Sql.values(row -> {
      retained.set(row);
      row.value(handles.getFirst(), 1L);
      throw failure;
    })));
    assertThrows(QueryValidationException.class, () -> Objects.requireNonNull(retained.get()).value(handles.getFirst(), 2L));
  }

  @Test void capturesMutableValuesAtEachAppend() {
    byte[] input = {1};
    var output = Sql.output(Sql.value(input), "bytes");
    var source = Sql.values(row -> {
      row.value(output, input);
      input[0] = 9;
    });
    assertArrayEquals(new byte[] {1}, (byte[]) ValuesSources.require(source).values[0]);
  }
}
