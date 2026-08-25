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
  void generatesATwentyColumnProjectionMapperWithoutNumberedFactories() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.TwentyColumnSummary",
            """
            package samples;
            import io.skis.annotations.SkisProjection;
            @SkisProjection
            public record TwentyColumnSummary(
                long id,
                String value1,
                String value2,
                String value3,
                String value4,
                String value5,
                String value6,
                String value7,
                String value8,
                String value9,
                String value10,
                String value11,
                String value12,
                String value13,
                String value14,
                String value15,
                String value16,
                String value17,
                String value18,
                String value19) {}
            """);
    CompilationResult result =
        process(
            sources,
            SkisProjectionProcessor.class.getName(),
            temporaryDirectory.resolve("twenty-column-projection"));

    assertTrue(result.success(), result.diagnosticsText());
    String generated = generatedSource(result, "TwentyColumnSummaryProjection.java");
    assertTrue(
        generated.contains(
            "public static <E> Projection<E, samples.TwentyColumnSummary> of("),
        generated);
    assertTrue(generated.contains("QueryColumn<E, java.lang.Long> id,"), generated);
    assertTrue(generated.contains("QueryColumn<E, java.lang.String> value19)"), generated);
    assertTrue(
        generated.contains(
            "private static final Projection.Mapping<samples.TwentyColumnSummary> MAPPING ="),
        generated);
    assertTrue(
        generated.contains("Projection.mapping(TwentyColumnSummaryProjection.class);"), generated);
    assertTrue(generated.contains("return Projection.generated(\n        MAPPING,"), generated);
    assertTrue(generated.contains("if (id.nullable())"), generated);
    assertTrue(generated.contains("$skisReaders.reader(19, value19);"), generated);
    assertTrue(generated.contains("new samples.TwentyColumnSummary("), generated);

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void usesTheExplicitProjectionConstructorForAClass() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.NamedSummary",
            """
            package samples;
            import io.skis.annotations.*;
            @SkisProjection
            public final class NamedSummary {
              public NamedSummary(Long ignored) {}
              @ProjectionConstructor
              public NamedSummary(Long id, String name) {}
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisProjectionProcessor.class.getName(),
            temporaryDirectory.resolve("projection-constructor"));

    assertTrue(result.success(), result.diagnosticsText());
    String generated = generatedSource(result, "NamedSummaryProjection.java");
    assertTrue(generated.contains("QueryColumn<E, java.lang.Long> id,"), generated);
    assertTrue(generated.contains("QueryColumn<E, java.lang.String> name)"), generated);
    assertFalse(generated.contains("ignored"), generated);

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void rejectsAnAmbiguousProjectionClassConstructor() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.InvalidProjection",
            """
            package samples;
            import io.skis.annotations.SkisProjection;
            @SkisProjection
            public final class InvalidProjection {
              public InvalidProjection(Long id) {}
              public InvalidProjection(Long id, String name) {}
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisProjectionProcessor.class.getName(),
            temporaryDirectory.resolve("ambiguous-projection-constructor"));

    assertFalse(result.success(), "processing unexpectedly succeeded");
    assertTrue(result.diagnosticsText().contains("[SKIS212]"), result.diagnosticsText());
  }

  @Test
  void rejectsAGenericProjectionConstructorBeforeGeneratingInvalidSource() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.GenericConstructorSummary",
            """
            package samples;
            import io.skis.annotations.SkisProjection;
            @SkisProjection
            public final class GenericConstructorSummary {
              public <T> GenericConstructorSummary(T value) {}
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisProjectionProcessor.class.getName(),
            temporaryDirectory.resolve("generic-projection-constructor"));

    assertFalse(result.success(), "processing unexpectedly succeeded");
    assertTrue(result.diagnosticsText().contains("[SKIS216]"), result.diagnosticsText());
  }

  @Test
  void retriesAProjectionWhoseParameterTypeIsGeneratedInALaterRound() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.DeferredProjection",
            """
            package samples;
            import io.skis.annotations.SkisProjection;
            @SkisProjection
            public record DeferredProjection(GeneratedMoney amount) {}
            """);
    String processors =
        SkisProjectionProcessor.class.getName()
            + ","
            + DeferredTypeGeneratorProcessor.class.getName();
    CompilationResult result =
        process(sources, processors, temporaryDirectory.resolve("deferred-projection-type"));

    assertTrue(result.success(), result.diagnosticsText());
    assertTrue(
        Files.exists(result.generatedSources().resolve("samples/GeneratedMoney.java")),
        "the collaborating processor did not generate the deferred type");
    assertTrue(
        Files.exists(
            result.generatedSources().resolve("samples/skis/DeferredProjectionProjection.java")),
        "SKIS did not retry the deferred projection");
    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void reportsAProjectionWhoseParameterTypeNeverBecomesAvailable() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.UnresolvedProjection",
            """
            package samples;
            import io.skis.annotations.SkisProjection;
            @SkisProjection
            public record UnresolvedProjection(MissingProjectionValue value) {}
            """);
    CompilationResult result =
        process(
            sources,
            SkisProjectionProcessor.class.getName(),
            temporaryDirectory.resolve("unresolved-projection-type"));

    assertFalse(result.success(), "processing unexpectedly succeeded");
    assertTrue(result.diagnosticsText().contains("[SKIS217]"), result.diagnosticsText());
  }

  @Test
  void rejectsAProjectionParameterTypeThatTheGeneratedPackageCannotAccess() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.HiddenProjectionValue",
            """
            package samples;
            final class HiddenProjectionValue {}
            """,
            "samples.InaccessibleProjection",
            """
            package samples;
            import io.skis.annotations.SkisProjection;
            @SkisProjection
            public record InaccessibleProjection(HiddenProjectionValue value) {}
            """);
    CompilationResult result =
        process(
            sources,
            SkisProjectionProcessor.class.getName(),
            temporaryDirectory.resolve("inaccessible-projection-type"));

    assertFalse(result.success(), "processing unexpectedly succeeded");
    assertTrue(result.diagnosticsText().contains("[SKIS218]"), result.diagnosticsText());
  }

  @Test
  void generatesStableRecordSourcesAndTheyCompileWithoutWarnings() throws Exception {
    Map<String, String> sources = Map.of("samples.Pet", resource("/samples/Pet.java"));
    CompilationResult result =
        process(sources, SkisEntityProcessor.class.getName(), temporaryDirectory.resolve("pet"));

    assertTrue(result.success(), result.diagnosticsText());
    assertGeneratedEquals(result, "PetMeta.java");
    assertGeneratedEquals(result, "PetTable.java");
    assertGeneratedEquals(result, "PetRowDecoder.java");
    assertGeneratedEquals(result, "PetBinder.java");
    assertGeneratedEquals(result, "PetRuntimeModel.java");

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void generatesBeanEntitySourcesUsingHandwrittenAccessors() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.BeanPet",
            """
            package samples;
            import io.skis.annotations.*;
            @SkisEntity
            @Table(name = "pet")
            public class BeanPet {
              @Id private Long id;
              @Column(name = "pet_name") private String name;
              @Version private Long version;
              @Transient private String displayLabel;

              public BeanPet() {}
              public Long getId() { return id; }
              public void setId(Long id) { this.id = id; }
              public String getName() { return name; }
              public void setName(String name) { this.name = name; }
              public Long getVersion() { return version; }
              public void setVersion(Long version) { this.version = version; }
            }
            """);
    CompilationResult result =
        process(
            sources, SkisEntityProcessor.class.getName(), temporaryDirectory.resolve("bean-pet"));

    assertTrue(result.success(), result.diagnosticsText());
    String meta = generatedSource(result, "BeanPetMeta.java");
    String decoder = generatedSource(result, "BeanPetRowDecoder.java");
    String binder = generatedSource(result, "BeanPetBinder.java");
    assertTrue(meta.contains("new ColumnMeta(\"pet_name\""), meta);
    assertFalse(meta.contains("displayLabel"), meta);
    assertTrue(decoder.contains("samples.BeanPet entity = new samples.BeanPet();"), decoder);
    assertTrue(decoder.contains("entity.setId("), decoder);
    assertTrue(decoder.contains("entity.setName("), decoder);
    assertTrue(decoder.contains("entity.setVersion("), decoder);
    assertTrue(binder.contains("entity.getId()"), binder);
    assertTrue(binder.contains("entity.getName()"), binder);
    assertTrue(binder.contains("entity.getVersion()"), binder);

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void supportsGetterAnnotationsFluentAccessorsAndPublicFields() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.FlexibleBean",
            """
            package samples;
            import io.skis.annotations.*;
            @SkisEntity
            public class FlexibleBean {
              private Long id;
              public String description;

              public FlexibleBean() {}
              @Id public Long id() { return id; }
              public FlexibleBean id(Long id) { this.id = id; return this; }
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("flexible-bean"));

    assertTrue(result.success(), result.diagnosticsText());
    String decoder = generatedSource(result, "FlexibleBeanRowDecoder.java");
    String binder = generatedSource(result, "FlexibleBeanBinder.java");
    assertTrue(decoder.contains("entity.id("), decoder);
    assertTrue(decoder.contains("entity.description = "), decoder);
    assertTrue(binder.contains("entity.id()"), binder);
    assertTrue(binder.contains("entity.description"), binder);

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void selectsUsableAccessorsAcrossBeanAndFluentCandidates() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.AlternativeAccessBean",
            """
            package samples;
            import io.skis.annotations.*;
            @SkisEntity
            public class AlternativeAccessBean {
              @Id private Long id;
              @Column(nullable = false) private boolean active;
              @Column(nullable = false) private boolean enabled;

              public AlternativeAccessBean() {}
              public Long getId() { return id; }
              private void setId(Long id) { this.id = id; }
              public AlternativeAccessBean id(Long id) { this.id = id; return this; }
              private boolean isActive() { return active; }
              public boolean getActive() { return active; }
              public void setActive(boolean active) { this.active = active; }
              public String isEnabled() { return Boolean.toString(enabled); }
              public boolean getEnabled() { return enabled; }
              public void setEnabled(String enabled) { this.enabled = Boolean.parseBoolean(enabled); }
              public AlternativeAccessBean enabled(boolean enabled) {
                this.enabled = enabled;
                return this;
              }
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("alternative-accessors"));

    assertTrue(result.success(), result.diagnosticsText());
    String decoder = generatedSource(result, "AlternativeAccessBeanRowDecoder.java");
    String binder = generatedSource(result, "AlternativeAccessBeanBinder.java");
    assertTrue(decoder.contains("entity.id("), decoder);
    assertTrue(decoder.contains("entity.setActive("), decoder);
    assertTrue(decoder.contains("entity.enabled("), decoder);
    assertTrue(binder.contains("entity.getId()"), binder);
    assertTrue(binder.contains("entity.getActive()"), binder);
    assertTrue(binder.contains("entity.getEnabled()"), binder);

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void supportsLombokStyleBooleanIsPrefixAccessors() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.BooleanPrefixBean",
            """
            package samples;
            import io.skis.annotations.*;
            @SkisEntity
            public class BooleanPrefixBean {
              @Id private Long id;
              private boolean isActive;

              public BooleanPrefixBean() {}
              public Long getId() { return id; }
              public void setId(Long id) { this.id = id; }
              @Column(nullable = false) public boolean isActive() { return isActive; }
              public void setActive(boolean active) { isActive = active; }
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("boolean-prefix"));

    assertTrue(result.success(), result.diagnosticsText());
    String decoder = generatedSource(result, "BooleanPrefixBeanRowDecoder.java");
    String binder = generatedSource(result, "BooleanPrefixBeanBinder.java");
    assertTrue(decoder.contains("RowLayout.contiguous(2, firstColumnIndex)"), decoder);
    assertTrue(decoder.contains("entity.setActive("), decoder);
    assertTrue(binder.contains("entity.isActive()"), binder);

    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void allowsSqlExceptionFromGeneratedBeanInvocationPoints() throws Exception {
    Map<String, String> sources =
        Map.of(
            "samples.SqlExceptionBean",
            """
            package samples;
            import io.skis.annotations.*;
            import java.sql.SQLException;
            @SkisEntity
            public class SqlExceptionBean {
              @Id private Long id;

              public SqlExceptionBean() throws SQLException {}
              public Long getId() throws SQLException { return id; }
              public void setId(Long id) throws SQLException { this.id = id; }
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("sql-exception-bean"));

    assertTrue(result.success(), result.diagnosticsText());
    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void waitsOneRoundForOptionalLombokShapeTransformationWithoutDependingOnLombok()
      throws Exception {
    Map<String, String> sources =
        Map.of(
            "lombok.Getter",
            """
            package lombok;
            import java.lang.annotation.*;
            @Target({ElementType.TYPE, ElementType.FIELD})
            @Retention(RetentionPolicy.SOURCE)
            public @interface Getter {}
            """,
            "samples.LombokStyleBean",
            """
            package samples;
            import io.skis.annotations.*;
            @lombok.Getter
            @SkisEntity
            public class LombokStyleBean {
              @Id private Long id;
              public LombokStyleBean() {}
              public Long getId() { return id; }
              public void setId(Long id) { this.id = id; }
            }
            """);
    String processors =
        SkisEntityProcessor.class.getName() + "," + RoundForcingProcessor.class.getName();
    CompilationResult result =
        process(sources, processors, temporaryDirectory.resolve("lombok-round"));

    assertTrue(result.success(), result.diagnosticsText());
    assertTrue(
        Files.exists(result.generatedSources().resolve("samples/skis/LombokStyleBeanMeta.java")),
        "SKIS did not retry the Lombok-shaped entity");
    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void doesNotDeferForLombokAccessorsAnnotationAlone() throws Exception {
    Map<String, String> sources =
        Map.of(
            "lombok.experimental.Accessors",
            """
            package lombok.experimental;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.SOURCE)
            public @interface Accessors {
              boolean fluent() default false;
            }
            """,
            "samples.AccessorsOnlyBean",
            """
            package samples;
            import io.skis.annotations.*;
            @lombok.experimental.Accessors(fluent = true)
            @SkisEntity
            public class AccessorsOnlyBean {
              @Id private Long id;
              public AccessorsOnlyBean() {}
              public Long id() { return id; }
              public AccessorsOnlyBean id(Long id) { this.id = id; return this; }
            }
            """);
    CompilationResult result =
        process(
            sources,
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("accessors-only"));

    assertTrue(result.success(), result.diagnosticsText());
    CompilationResult generatedCompilation = compileGenerated(sources, result);
    assertTrue(generatedCompilation.success(), generatedCompilation.diagnosticsText());
  }

  @Test
  void reportsMissingPublicNoArgsConstructorForClassEntity() throws Exception {
    assertProcessingError(
        "SKIS033",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public class Invalid {
          @Id private Long id;
          public Invalid(Long id) { this.id = id; }
          public Long getId() { return id; }
          public void setId(Long id) { this.id = id; }
        }
        """);
  }

  @Test
  void reportsIncompatibleCheckedExceptionFromNoArgsConstructor() throws Exception {
    assertProcessingError(
        "SKIS039",
        """
        package samples;
        import io.skis.annotations.*;
        import java.io.IOException;
        @SkisEntity
        public class Invalid {
          @Id public Long id;
          public Invalid() throws IOException {}
        }
        """);
  }

  @Test
  void reportsMissingReadableClassProperty() throws Exception {
    assertProcessingError(
        "SKIS034",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public class Invalid {
          @Id private Long id;
          public Invalid() {}
          public void setId(Long id) { this.id = id; }
        }
        """);
  }

  @Test
  void reportsMissingWritableClassProperty() throws Exception {
    assertProcessingError(
        "SKIS035",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public class Invalid {
          @Id private Long id;
          public Invalid() {}
          public Long getId() { return id; }
        }
        """);
  }

  @Test
  void reportsConflictingFieldAndGetterColumnMappings() throws Exception {
    assertProcessingError(
        "SKIS036",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public class Invalid {
          @Id @Column(name = "field_id") private Long id;
          public Invalid() {}
          @Column(name = "getter_id") public Long getId() { return id; }
          public void setId(Long id) { this.id = id; }
        }
        """);
  }

  @Test
  void reportsClassEntityInheritanceBeforeItCanSilentlyLoseProperties() throws Exception {
    assertProcessingError(
        "SKIS032",
        """
        package samples;
        import io.skis.annotations.*;
        class Base { protected String inherited; }
        @SkisEntity
        public class Invalid extends Base {
          @Id public Long id;
          public Invalid() {}
        }
        """);
  }

  @Test
  void reportsAbstractClassEntity() throws Exception {
    assertProcessingError(
        "SKIS031",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public abstract class Invalid {
          @Id private Long id;
          public Invalid() {}
          public Long getId() { return id; }
          public void setId(Long id) { this.id = id; }
        }
        """);
  }

  @Test
  void reportsAccessorTypeMismatch() throws Exception {
    assertProcessingError(
        "SKIS037",
        """
        package samples;
        import io.skis.annotations.*;
        @SkisEntity
        public class Invalid {
          @Id private Long id;
          public Invalid() {}
          public String getId() { return id == null ? null : id.toString(); }
          public void setId(Long id) { this.id = id; }
        }
        """);
  }

  @Test
  void reportsIncompatibleCheckedExceptionFromGetter() throws Exception {
    assertProcessingError(
        "SKIS040",
        """
        package samples;
        import io.skis.annotations.*;
        import java.io.IOException;
        @SkisEntity
        public class Invalid {
          @Id private Long id;
          public Invalid() {}
          public Long getId() throws IOException { return id; }
          public void setId(Long id) { this.id = id; }
        }
        """);
  }

  @Test
  void reportsIncompatibleCheckedExceptionFromSetter() throws Exception {
    assertProcessingError(
        "SKIS040",
        """
        package samples;
        import io.skis.annotations.*;
        import java.io.IOException;
        @SkisEntity
        public class Invalid {
          @Id private Long id;
          public Invalid() {}
          public Long getId() { return id; }
          public void setId(Long id) throws IOException { this.id = id; }
        }
        """);
  }

  @Test
  void reportsLombokShapeThatNeverSettles() throws Exception {
    CompilationResult result =
        process(
            Map.of(
                "lombok.Getter",
                """
                package lombok;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface Getter {}
                """,
                "samples.Invalid",
                """
                package samples;
                import io.skis.annotations.*;
                @lombok.Getter
                @SkisEntity
                public class Invalid {
                  @Id private Long id;
                  public Invalid() {}
                  public Long getId() { return id; }
                  public void setId(Long id) { this.id = id; }
                }
                """),
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("SKIS038"));

    assertFalse(result.success(), "processing unexpectedly succeeded");
    assertTrue(result.diagnosticsText().contains("[SKIS038]"), result.diagnosticsText());
  }

  @Test
  void reportsLombokBuilderShapeThatNeverSettles() throws Exception {
    CompilationResult result =
        process(
            Map.of(
                "lombok.Builder",
                """
                package lombok;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface Builder {}
                """,
                "samples.Invalid",
                """
                package samples;
                import io.skis.annotations.*;
                @lombok.Builder
                @SkisEntity
                public class Invalid {
                  @Id public Long id;
                }
                """),
            SkisEntityProcessor.class.getName(),
            temporaryDirectory.resolve("lombok-builder-SKIS038"));

    assertFalse(result.success(), "processing unexpectedly succeeded");
    assertTrue(result.diagnosticsText().contains("[SKIS038]"), result.diagnosticsText());
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
            result.generatedSources().resolve("samples/skis/ImplicitNonNullMeta.java"),
            StandardCharsets.UTF_8);
    assertTrue(generated.contains("new ColumnMeta(\"id\", false"), generated);
    assertTrue(generated.contains("new ColumnMeta(\"name\", false"), generated);
    assertTrue(generated.contains("new ColumnMeta(\"lock_version\", false"), generated);
    String decoder = generatedSource(result, "ImplicitNonNullRowDecoder.java");
    String binder = generatedSource(result, "ImplicitNonNullBinder.java");
    assertTrue(decoder.contains("requireReadValue(JdbcCodecs.readNullableLong("), decoder);
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
        SkisEntityProcessor.class.getName() + "," + DeferredTypeGeneratorProcessor.class.getName();
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
        ("# skis-generated-abi=3\n"
                + "samples.skis.AlphaRuntimeModel\n"
                + "samples.skis.ZuluRuntimeModel\n")
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
          Boolean.TRUE.equals(task.call()),
          diagnostics.getDiagnostics(),
          generatedSources,
          classes);
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
