package io.skis.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkisEntityProcessorTest {

  @TempDir Path temporaryDirectory;

  @Test
  void generatesStableRecordSourcesAndTheyCompileWithoutWarnings() throws Exception {
    Map<String, String> sources = Map.of("samples.Book", resource("/samples/Book.java"));
    CompilationResult result =
        process(sources, SkisEntityProcessor.class.getName(), temporaryDirectory.resolve("book"));

    assertTrue(result.success(), result.diagnosticsText());
    assertGeneratedEquals(result, "BookMeta.java");
    assertGeneratedEquals(result, "BookTable.java");
    assertGeneratedEquals(result, "BookRowDecoder.java");
    assertGeneratedEquals(result, "BookBinder.java");

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void reportsVersionPrimaryKeyAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS024",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id @Version @Column(nullable = false) Long id) {}
        """);
  }

  @Test
  void infersIdAndVersionColumnsAsNonNullable() throws Exception {
    CompilationResult result =
        process(
            Map.of(
                "samples.ImplicitNonNull",
                """
                package samples;
                import io.skis.annotations.*;
                @SkisEntity
                public record ImplicitNonNull(
                    @Id Long id,
                    @Column(nullable = false) String name,
                    @Version @Column(name = "lock_version") Long version) {}
                """),
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("implicit-non-null"));

    assertTrue(result.success(), result.diagnosticsText());
    String generated =
        Files.readString(
            result
                .generatedSources()
                .resolve("samples/skis/ImplicitNonNullMeta.java"),
            StandardCharsets.UTF_8);
    assertTrue(generated.contains("new ColumnMeta(\"id\", false"), generated);
    assertTrue(generated.contains("new ColumnMeta(\"name\", false"), generated);
    assertTrue(generated.contains("new ColumnMeta(\"lock_version\", false"), generated);
    String decoder = generatedSource(result, "ImplicitNonNullRowDecoder.java");
    String binder = generatedSource(result, "ImplicitNonNullBinder.java");
    assertTrue(
        decoder.contains("requireReadValue(JdbcCodecs.readNullableLong("), decoder);
    assertTrue(decoder.contains("requireReadValue(JdbcCodecs.readString("), decoder);
    assertTrue(binder.contains("requireBindValue(entity.id(), index)"), binder);
    assertTrue(binder.contains("requireBindValue(entity.name(), index)"), binder);
    assertTrue(binder.contains("requireBindValue(entity.version(), index)"), binder);
  }

  @Test
  void reportsExplicitNullableIdAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS008",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id @Column(nullable = true) Long id) {}
        """);
  }

  @Test
  void reportsExplicitNullableVersionAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS011",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id Long id,
            @Version @Column(nullable = true) Long version) {}
        """);
  }

  @Test
  void reportsNonInsertableVersionAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS030",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id Long id,
            @Version @Column(insertable = false) Long version) {}
        """);
  }

  @Test
  void reportsNonUpdatableVersionAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS030",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id Long id,
            @Version @Column(updatable = false) Long version) {}
        """);
  }

  @Test
  void reportsFloatingPointVersionAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS010",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id Long id,
            @Version Double version) {}
        """);
  }

  @Test
  void reportsPrimitiveNullableColumnAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS023",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id @Column(nullable = false) long id,
            int quantity) {}
        """);
  }

  @Test
  void reportsInvalidColumnShapeAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS026",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id @Column(nullable = false) Long id,
            @Column(length = -1) String name) {}
        """);
  }

  @Test
  void reportsAmbiguousCompositeKeyAtCompileTime() throws Exception {
    assertProcessingError(
        "SKIS020",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(
            @Id @Column(nullable = false) Long tenantId,
            @Id @Column(nullable = false) Long id) {}
        """);
  }

  @Test
  void generatesDedicatedCodecsForSupportedStandardTypes() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.StandardTypes",
            """
            package samples;
            import io.skis.annotations.*;
            import java.math.BigInteger;
            import java.sql.Date;
            import java.sql.Time;
            import java.sql.Timestamp;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.LocalDateTime;
            import java.time.LocalTime;
            import java.time.OffsetDateTime;
            import java.time.OffsetTime;
            import java.util.UUID;
            @SkisEntity
            public record StandardTypes(
                @Id long id,
                @Column(nullable = false) char code,
                Character optionalCode,
                BigInteger bigIntegerValue,
                UUID uuidValue,
                Instant instantValue,
                LocalDate localDateValue,
                LocalTime localTimeValue,
                LocalDateTime localDateTimeValue,
                OffsetTime offsetTimeValue,
                OffsetDateTime offsetDateTimeValue,
                Date sqlDateValue,
                Time sqlTimeValue,
                Timestamp sqlTimestampValue) {}
            """);
    CompilationResult result =
        process(
            sources,
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("standard-types"));

    assertTrue(result.success(), result.diagnosticsText());
    String decoder = generatedSource(result, "StandardTypesRowDecoder.java");
    String binder = generatedSource(result, "StandardTypesBinder.java");
    for (String method :
        List.of(
            "readChar",
            "readNullableChar",
            "readBigInteger",
            "readUuid",
            "readInstant",
            "readLocalDate",
            "readLocalTime",
            "readLocalDateTime",
            "readOffsetTime",
            "readOffsetDateTime",
            "readSqlDate",
            "readSqlTime",
            "readSqlTimestamp")) {
      assertTrue(decoder.contains("JdbcCodecs." + method + "("), decoder);
    }
    for (String method :
        List.of(
            "bindChar",
            "bindNullableChar",
            "bindBigInteger",
            "bindUuid",
            "bindInstant",
            "bindLocalDate",
            "bindLocalTime",
            "bindLocalDateTime",
            "bindOffsetTime",
            "bindOffsetDateTime",
            "bindSqlDate",
            "bindSqlTime",
            "bindSqlTimestamp")) {
      assertTrue(binder.contains("JdbcCodecs." + method + "("), binder);
    }
    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void rejectsPersistentTypesWithoutAnExplicitCodec() throws Exception {
    assertProcessingError(
        "SKIS022",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(@Id Long id, Money amount) {}
        record Money(long cents) {}
        """);
  }

  @Test
  void retriesAnEntityWhenAnotherProcessorGeneratesItsPropertyType() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.DeferredEntity",
            """
            package samples;
            import io.skis.annotations.*;
            @SkisEntity
            public record DeferredEntity(
                @Id Long id,
                @Transient GeneratedMoney amount) {}
            """);
    String processors =
        SkisEntityProcessor.class.getName()
            + ","
            + DeferredTypeGeneratorProcessor.class.getName();
    CompilationResult result =
        process(sources, processors, temporaryDirectory.resolve("deferred-type"));

    assertTrue(result.success(), result.diagnosticsText());
    assertTrue(
        Files.exists(result.generatedSources().resolve("samples/GeneratedMoney.java")),
        "the collaborating processor did not generate the deferred type");
    assertTrue(
        Files.exists(result.generatedSources().resolve("samples/skis/DeferredEntityMeta.java")),
        "SKIS did not retry the deferred entity");
    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void reportsAnEntityWhosePropertyTypeNeverBecomesAvailable() throws Exception {
    assertProcessingError(
        "SKIS097",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public record Invalid(@Id Long id, MissingType amount) {}
        """);
  }

  @Test
  void writesSortedAggregatedEntityIndex() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.Zulu",
            """
            package samples;
            import io.skis.annotations.SkisEntity;
            @SkisEntity(readOnly = true)
            public record Zulu(String value) {}
            """,
            "samples.Alpha",
            """
            package samples;
            import io.skis.annotations.SkisEntity;
            @SkisEntity(readOnly = true)
            public record Alpha(String value) {}
            """);
    String processors =
        SkisEntityProcessor.class.getName() + "," + SkisEntityIndexProcessor.class.getName();
    CompilationResult result = process(sources, processors, temporaryDirectory.resolve("index"));

    assertTrue(result.success(), result.diagnosticsText());
    assertArrayEquals(
        ("# skis-generated-abi=1\n"
                + "samples.skis.AlphaMeta\n"
                + "samples.skis.ZuluMeta\n")
            .getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(result.classes().resolve("META-INF/skis/entities.idx")));
  }

  private void assertProcessingError(String code, String source) throws Exception {
    CompilationResult result =
        process(
            Map.of("samples.Invalid", source),
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve(code));
    assertFalse(result.success(), "processing unexpectedly succeeded");
    assertTrue(result.diagnosticsText().contains("[" + code + "]"), result.diagnosticsText());
  }

  private static CompilationResult process(
      Map<String, String> sources, String processors, Path output) throws IOException {
    Files.createDirectories(output);
    Path generatedSources = output.resolve("generated");
    Path classes = output.resolve("classes");
    Files.createDirectories(generatedSources);
    Files.createDirectories(classes);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<JavaFileObject> sourceFiles = sourceFiles(sources);
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      JavaCompiler.CompilationTask task =
          compiler.getTask(
              null,
              fileManager,
              diagnostics,
              List.of(
                  "-proc:only",
                  "-processor",
                  processors,
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-s",
                  generatedSources.toString(),
                  "-d",
                  classes.toString()),
              null,
              sourceFiles);
      return new CompilationResult(
          Boolean.TRUE.equals(task.call()), diagnostics.getDiagnostics(), generatedSources, classes);
    }
  }

  private CompilationResult compileGenerated(
      Map<String, String> originalSources, CompilationResult processingResult) throws IOException {
    Path classes = temporaryDirectory.resolve("compiled-generated");
    Files.createDirectories(classes);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        var paths = Files.walk(processingResult.generatedSources())) {
      List<Path> generatedPaths = paths.filter(path -> path.toString().endsWith(".java")).toList();
      List<JavaFileObject> allSources = new ArrayList<>(sourceFiles(originalSources));
      fileManager.getJavaFileObjectsFromPaths(generatedPaths).forEach(allSources::add);
      JavaCompiler.CompilationTask task =
          compiler.getTask(
              null,
              fileManager,
              diagnostics,
              List.of(
                  "-proc:none",
                  "-Xlint:all",
                  "-Werror",
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-d",
                  classes.toString()),
              null,
              allSources);
      return new CompilationResult(
          Boolean.TRUE.equals(task.call()),
          diagnostics.getDiagnostics(),
          processingResult.generatedSources(),
          classes);
    }
  }

  private static List<JavaFileObject> sourceFiles(Map<String, String> sources) {
    return sources.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> (JavaFileObject) new SourceFile(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static void assertGeneratedEquals(CompilationResult result, String fileName)
      throws IOException {
    Path generatedFile = result.generatedSources().resolve("samples/skis").resolve(fileName);
    assertTrue(Files.exists(generatedFile), "missing generated source " + generatedFile);
    assertArrayEquals(
        resourceBytes("/samples/" + fileName + ".expected"),
        Files.readAllBytes(generatedFile),
        "generated source differs from its golden file: " + fileName);
  }

  private static String generatedSource(CompilationResult result, String fileName)
      throws IOException {
    return Files.readString(
        result.generatedSources().resolve("samples/skis").resolve(fileName),
        StandardCharsets.UTF_8);
  }

  private static String resource(String name) throws IOException {
    return new String(resourceBytes(name), StandardCharsets.UTF_8);
  }

  private static byte[] resourceBytes(String name) throws IOException {
    try (var input = SkisEntityProcessorTest.class.getResourceAsStream(name)) {
      if (input == null) {
        throw new IOException("missing test resource " + name);
      }
      return input.readAllBytes();
    }
  }

  private record CompilationResult(
      boolean success,
      List<Diagnostic<? extends JavaFileObject>> diagnostics,
      Path generatedSources,
      Path classes) {

    String diagnosticsText() {
      return diagnostics.stream()
          .map(
              diagnostic -> {
                String source =
                    diagnostic.getSource() == null ? "<unknown>" : diagnostic.getSource().getName();
                return diagnostic.getKind()
                    + ": "
                    + source
                    + ":"
                    + diagnostic.getLineNumber()
                    + ":"
                    + diagnostic.getColumnNumber()
                    + ": "
                    + diagnostic.getMessage(null);
              })
          .reduce((left, right) -> left + "\n" + right)
          .orElse("no compiler diagnostics");
    }
  }

  private static final class SourceFile extends SimpleJavaFileObject {
    private final String source;

    private SourceFile(String className, String source) {
      super(
          URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
          Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
