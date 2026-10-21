package io.skis.processor;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Companion to the plain-javac query/mutation nominal-type contracts. */
@EnabledIfSystemProperty(named = "skis.staticAnalysis", matches = "true")
class NullnessCompilationTest {

  @TempDir Path directory;

  @Test
  void refusesMissingConfigurationAndMismatchedToolVersions() {
    String version = System.getProperty("skis.nullaway.version");
    try {
      System.setProperty("skis.nullaway.version", "unexpected-version");
      assertThrows(IllegalStateException.class,
          () -> check("invalid-tool-version", "static int value() { return 1; }"));
    } finally {
      if (version == null) System.clearProperty("skis.nullaway.version");
      else System.setProperty("skis.nullaway.version", version);
    }
    String checks = System.getProperty("skis.staticAnalysis.checks");
    try {
      System.clearProperty("skis.staticAnalysis.checks");
      assertThrows(IllegalStateException.class,
          () -> check("missing-checker-configuration", "static int value() { return 1; }"));
    } finally {
      if (checks == null) System.clearProperty("skis.staticAnalysis.checks");
      else System.setProperty("skis.staticAnalysis.checks", checks);
    }
  }

  @Test
  void checkerLoadingAndBothExplicitMarkingRulesAreMandatory() throws Exception {
    check("guarded", """
        static int length(@Nullable String value) {
          return value == null ? 0 : value.length();
        }
        """).assertPass();
    reject("dereference", "static int length(@Nullable String value) { return value.length(); }");
    NullnessCompiler.compile("unmarked-package", Map.of("unmarked.Unmarked", """
        package unmarked;
        public class Unmarked {}
        """), directory.resolve("unmarked"))
        .assertRejected("RequireExplicitNullMarking", "Unmarked.java");
    check("meaningless-annotation", "static @Nullable int value() { return 1; }")
        .assertRejected("JSpecifyUnrecognizedAnnotationLocation", "Contract.java");
    check("error-prone-default-error", "static void fail() { new IllegalStateException(); }")
        .assertRejected("DeadException", "Contract.java");
  }

  @Test
  void nullableQueryResultsStayNullableInEveryTerminalAndCallback() throws Exception {
    check("query-terminals-valid", """
        static int read(SelectQuery<@Nullable String> nullable, SelectQuery<String> required) {
          String safe = required.fetchOne().get();
          int[] callbackLength = {0};
          nullable.fetchOne().ifPresent(value -> callbackLength[0] += value.length());
          int mappedLength = nullable.fetchOne().map(String::length).orElse(0);
          List<@Nullable String> rows = nullable.fetchList();
          List<@Nullable String> page = nullable.fetchPage(new PageRequest(0, 10)).items();
          List<@Nullable String> slice = nullable.fetchSlice(SliceRequest.offset(0, 10)).items();
          List<@Nullable String> keyset = nullable.fetchSlice(SliceRequest.keysetFirst(10)).items();
          try (QueryCursor<@Nullable String> cursor = nullable.cursor();
               Stream<@Nullable String> stream = nullable.stream()) {
            stream.forEach(value -> { if (value != null) callbackLength[0] += value.length(); });
            if (cursor.advance()) {
              String value = cursor.current();
              if (value != null) return value.length();
            }
          }
          return safe.length() + rows.size() + page.size() + slice.size() + keyset.size()
              + callbackLength[0] + mappedLength;
        }
        static <R extends @Nullable Object> SingleRow<R> one(SelectQuery<R> query) {
          return query.fetchOne();
        }
        static <R extends @Nullable Object> SingleRow<R> first(SelectQuery<R> query) {
          return query.fetchFirst();
        }
        """).assertPass();
    Map<String, String> mistakes = Map.of(
        "single-row", "return query.fetchOne().orElse(null).length();",
        "list-element", "return query.fetchList().getFirst().length();",
        "page-element", "return query.fetchPage(new PageRequest(0, 10)).items().getFirst().length();",
        "slice-element", "return query.fetchSlice(SliceRequest.offset(0, 10)).items().getFirst().length();",
        "keyset-element", "return query.fetchSlice(SliceRequest.keysetFirst(10)).items().getFirst().length();",
        "cursor-element", "try (var cursor = query.cursor()) { return cursor.current().length(); }",
        "stream-callback", "try (var stream = query.stream()) { return stream.mapToInt(String::length).sum(); }");
    for (var mistake : mistakes.entrySet()) {
      reject("query-" + mistake.getKey(),
          "static int invalid(SelectQuery<@Nullable String> query) { " + mistake.getValue() + " }");
    }
    reject("query-result-generic", """
        static SelectQuery<String> invalid(SelectQuery<@Nullable String> query) { return query; }
        """);
    reject("query-list-generic", """
        static List<String> invalid(SelectQuery<@Nullable String> query) { return query.fetchList(); }
        """);
  }

  @Test
  void decodedSingleRowsRetainTheirTypeWithoutWeakeningPublicFactories() throws Exception {
    NullnessCompiler.compile("decoded-single-row-valid", Map.of("io.skis.query.DecodedRowContract", """
        package io.skis.query;
        import org.jspecify.annotations.*;
        @NullMarked final class DecodedRowContract {
          static <R extends @Nullable Object> SingleRow<R> decoded(
              @Nullable R value, boolean nullableResult) {
            return SingleRow.<R>decoded(value, nullableResult);
          }
          static String required(String value) {
            return DecodedRowContract.<String>decoded(value, false).get();
          }
          static @Nullable String nullable(@Nullable String value) {
            return DecodedRowContract.<@Nullable String>decoded(value, true).orElse(null);
          }
          static SingleRow<@Nullable String> present(@Nullable String value) {
            return SingleRow.<@Nullable String>present(value);
          }
        }
        """), directory.resolve("decoded-single-row-valid")).assertPass();
    reject("single-row-present-input", """
        static SingleRow<String> invalid(@Nullable String value) {
          return SingleRow.<String>present(value);
        }
        """);
    reject("single-row-present-result", """
        static SingleRow<String> invalid(@Nullable String value) {
          return SingleRow.present(value);
        }
        """);
  }

  @Test
  void jdbcPageAndCursorRetainTheirResultTypeNullness() throws Exception {
    check("jdbc-results-valid", """
        static int required(JdbcExecutor executor, CompiledQueryPlan<String, Object> content,
            CompiledQueryPlan<Long, Object> count, Object parameters) {
          JdbcPageResult<String> page = executor.fetchPage(
              content, parameters, count, parameters, ExecutionContext.EMPTY);
          int length = page.items().getFirst().length();
          try (JdbcCursor<String> cursor = executor.openCursor(
              content, parameters, ExecutionContext.EMPTY)) {
            if (cursor.advance()) length += cursor.current().length();
          }
          return length;
        }
        static int nullable(JdbcExecutor executor,
            CompiledQueryPlan<@Nullable String, Object> content,
            CompiledQueryPlan<Long, Object> count, Object parameters) {
          JdbcPageResult<@Nullable String> page = executor.fetchPage(
              content, parameters, count, parameters, ExecutionContext.EMPTY);
          String first = page.items().getFirst();
          int length = first == null ? 0 : first.length();
          try (JdbcCursor<@Nullable String> cursor = executor.openCursor(
              content, parameters, ExecutionContext.EMPTY)) {
            if (cursor.advance()) {
              String value = cursor.current();
              if (value != null) length += value.length();
            }
          }
          return length;
        }
        static <R extends @Nullable Object> JdbcPageResult<R> generic(JdbcExecutor executor,
            CompiledQueryPlan<R, Object> content,
            CompiledQueryPlan<Long, Object> count, Object parameters) {
          return executor.fetchPage(content, parameters, count, parameters, ExecutionContext.EMPTY);
        }
        """).assertPass();
    reject("jdbc-page-element", """
        static int invalid(JdbcPageResult<@Nullable String> page) {
          return page.items().getFirst().length();
        }
        """);
    reject("jdbc-cursor-element", """
        static int invalid(JdbcCursor<@Nullable String> cursor) {
          return cursor.current().length();
        }
        """);
    reject("jdbc-page-result-type", """
        static JdbcPageResult<String> invalid(JdbcExecutor executor,
            CompiledQueryPlan<@Nullable String, Object> content,
            CompiledQueryPlan<Long, Object> count, Object parameters) {
          return executor.fetchPage(content, parameters, count, parameters, ExecutionContext.EMPTY);
        }
        """);
  }

  @Test
  void mappingAndMutationCallbacksEnforceInputAndOutputContracts() throws Exception {
    check("mapping-mutation-valid", """
        static RowDecoder<@Nullable String> nullableDecoder() { return (rs, context) -> null; }
        static RowDecoder<String> requiredDecoder() { return (rs, context) -> "ok"; }
        static int write(MutationOperations operations, QueryTable<String> table, String entity) {
          return operations.insert(table, entity);
        }
        static io.skis.runtime.TransactionCallback<@Nullable String> nullableTransaction() {
          return session -> null;
        }
        """).assertPass();
    reject("decoder-null-output", """
        static RowDecoder<String> invalid() { return (rs, context) -> null; }
        """);
    reject("codec-null-output", """
        static int invalid(JdbcTypeCodec<String> codec, ResultSet rs, JdbcReadContext context)
            throws SQLException { return codec.read(rs, 1, context).length(); }
        """);
    reject("reader-null-output", """
        static int invalid(ProjectionMapping.ValueReader<@Nullable String> reader,
            ResultSet rs, RowReadContext context) throws SQLException {
          return reader.read(rs, context).length();
        }
        """);
    reject("binder-null-input", """
        static void invalid(ParameterBinder<String> binder, PreparedStatement statement,
            JdbcWriteContext context) throws SQLException { binder.bind(statement, 1, null, context); }
        """);
    reject("mutation-null-entity", """
        static int invalid(MutationOperations operations, QueryTable<String> table,
            @Nullable String value) { return operations.insert(table, value); }
        """);
    reject("mutation-null-id", """
        static int invalid(MutationOperations operations, QueryTable<String> table) {
          return operations.deleteById(table, null);
        }
        """);
    reject("transaction-null-output", """
        static io.skis.runtime.TransactionCallback<String> invalid() { return session -> null; }
        """);
    reject("transaction-nullable-result", """
        static int invalid(io.skis.runtime.SkisExecutor executor,
            io.skis.runtime.TransactionCallback<@Nullable String> callback) {
          return executor.inTransaction(callback).length();
        }
        """);
  }

  @Test
  void genericAndWildcardRelationshipsRetainNullness() throws Exception {
    check("generic-wildcard-valid", """
        static <T extends @Nullable Object> T identity(T value) { return value; }
        static String valid(@Nullable String value, List<? extends @Nullable String> source,
            List<? super @Nullable String> sink) {
          sink.add(source.getFirst());
          return Objects.requireNonNull(identity(value));
        }
        """).assertPass();
    reject("generic-inference", """
        static <T extends @Nullable Object> T identity(T value) { return value; }
        static String invalid(@Nullable String value) { return identity(value); }
        """);
    reject("wildcard-element", """
        static int invalid(List<? extends @Nullable String> source) {
          return source.getFirst().length();
        }
        """);
    reject("wildcard-super", """
        static void invalid(List<? super String> target, @Nullable String value) { target.add(value); }
        """);
  }

  @Test
  void annotationsAreReadFromSeparatelyCompiledBytecode() throws Exception {
    Path api = directory.resolve("bytecode-api");
    NullnessCompiler.compile("bytecode-api", Map.of("separate.Api", """
        package separate;
        import java.util.List;
        import org.jspecify.annotations.*;
        @NullMarked public final class Api {
          public static List<@Nullable String> values() {
            List<@Nullable String> values = new java.util.ArrayList<>();
            values.add("ok");
            values.add(null);
            return values;
          }
        }
        """), api).assertPass();
    for (boolean valid : List.of(true, false)) {
      String body = valid
          ? "String value = separate.Api.values().getFirst(); return value == null ? 0 : value.length();"
          : "return separate.Api.values().getFirst().length();";
      var result = NullnessCompiler.compile("bytecode-consumer-" + valid,
          source("static int read() { " + body + " }"), List.of(), List.of(api),
          directory.resolve("bytecode-consumer-" + valid));
      if (valid) result.assertPass();
      else result.assertRejected("NullAway", "Contract.java");
    }
  }

  private NullnessCompiler.Result check(String id, String body) throws Exception {
    return NullnessCompiler.compile(id, source(body), directory.resolve(id));
  }

  private void reject(String id, String body) throws Exception {
    check(id, body).assertRejected("NullAway", "Contract.java");
  }

  private static Map<String, String> source(String body) {
    return Map.of("contracts.Contract", """
        package contracts;
        import io.skis.core.spi.ExecutionContext;
        import io.skis.jdbc.internal.CompiledQueryPlan;
        import io.skis.jdbc.internal.JdbcCursor;
        import io.skis.jdbc.internal.JdbcExecutor;
        import io.skis.jdbc.internal.JdbcPageResult;
        import io.skis.query.*;
        import io.skis.query.spi.ProjectionMapping;
        import io.skis.mapping.spi.*;
        import io.skis.mutation.MutationOperations;
        import java.sql.*;
        import java.util.*;
        import java.util.stream.Stream;
        import org.jspecify.annotations.*;
        @NullMarked final class Contract {
        %s
        }
        """.formatted(body));
  }
}
