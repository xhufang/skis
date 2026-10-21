package io.skis.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Test-only complete attribution/code-generation channel; never used by proc-only fixtures. */
final class NullnessCompiler {

  private NullnessCompiler() {}

  static Result compile(String id, Map<String, String> sources, Path output) throws Exception {
    return compile(id, sources, List.of(), List.of(), output);
  }

  static Result compile(
      String id,
      Map<String, String> sources,
      List<Path> generatedSources,
      List<Path> extraClasspath,
      Path output)
      throws Exception {
    if (!Boolean.getBoolean("skis.staticAnalysis")) {
      throw new IllegalStateException("nullness contracts require -Pstatic-analysis");
    }
    requireTool("com.google.errorprone.ErrorProneJavacPlugin", "error_prone_core",
        "skis.errorProne.version");
    requireTool("com.uber.nullaway.NullAway", "nullaway", "skis.nullaway.version");
    String checks = System.getProperty("skis.staticAnalysis.checks");
    if (checks == null || !checks.startsWith("-Xplugin:ErrorProne ")) {
      throw new IllegalStateException("missing explicit checker configuration");
    }
    String toolClasspath = System.getProperty("surefire.test.class.path",
        System.getProperty("java.class.path"));
    String classpath = toolClasspath;
    for (Path entry : extraClasspath) {
      classpath += java.io.File.pathSeparator + entry;
    }
    Files.createDirectories(output);
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("a full JDK is required for nullness contracts");
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    StringWriter compilerOutput = new StringWriter();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      List<JavaFileObject> units = new ArrayList<>();
      sources.entrySet().stream().sorted(Map.Entry.comparingByKey())
          .forEach(entry -> units.add(new Source(entry.getKey(), entry.getValue())));
      manager.getJavaFileObjectsFromPaths(generatedSources).forEach(units::add);
      List<String> options = List.of(
          "--release", "21", "-proc:none", "-classpath", classpath,
          "-processorpath", toolClasspath, "-d", output.toString(),
          "-XDcompilePolicy=simple", "--should-stop=ifError=FLOW",
          "-XDaddTypeAnnotationsToSymbol=true", checks);
      Path report = Path.of("target", "nullness-reports", id + ".txt");
      Files.createDirectories(report.getParent());
      // Snapshot inputs before javac so generated-source mutations or compiler crashes cannot
      // leave the CI artifact pointing only at deleted temporary files.
      writeInputs(report, options, units, extraClasspath);
      try {
        boolean success = Boolean.TRUE.equals(
            compiler.getTask(compilerOutput, manager, diagnostics, options, null, units).call());
        Result result = new Result(success, List.copyOf(diagnostics.getDiagnostics()),
            compilerOutput.toString());
        Files.writeString(report, "\nsuccess=" + success + "\n\n=== DIAGNOSTICS ===\n" + result.text(),
            StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return result;
      } catch (RuntimeException | Error failure) {
        failure.printStackTrace(new PrintWriter(compilerOutput));
        Result result = new Result(false, List.copyOf(diagnostics.getDiagnostics()),
            compilerOutput.toString());
        try {
          Files.writeString(report, "\nsuccess=false\ncompilerFailure=" + failure.getClass().getName()
                  + "\n\n=== DIAGNOSTICS ===\n" + result.text(),
              StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException reportFailure) {
          failure.addSuppressed(reportFailure);
        }
        throw failure;
      }
    }
  }

  private static void writeInputs(
      Path report, List<String> options, List<JavaFileObject> units, List<Path> extraClasspath)
      throws IOException {
    StringBuilder text = new StringBuilder();
    text.append("java=").append(Runtime.version())
        .append("\nrelease=21\nerrorProne=").append(System.getProperty("skis.errorProne.version"))
        .append("\nnullaway=").append(System.getProperty("skis.nullaway.version"))
        .append("\nextraClasspath=").append(extraClasspath)
        .append("\n\n=== JAVAC OPTIONS (one argument per entry) ===\n");
    for (int index = 0; index < options.size(); index++) {
      text.append('[').append(index).append("] ").append(options.get(index)).append('\n');
    }
    for (JavaFileObject unit : units) {
      text.append("\n=== SOURCE ").append(unit.toUri()).append(" ===\n")
          .append(unit.getCharContent(true)).append("\n=== END SOURCE ===\n");
    }
    Files.writeString(report, text, StandardCharsets.UTF_8);
  }

  private static void requireTool(String className, String artifact, String versionProperty)
      throws Exception {
    String version = System.getProperty(versionProperty);
    if (version == null || version.isBlank()) {
      throw new IllegalStateException("missing pinned version: " + versionProperty);
    }
    Class<?> tool = Class.forName(className, false, NullnessCompiler.class.getClassLoader());
    Path location = Path.of(tool.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (!location.getFileName().toString().equals(artifact + "-" + version + ".jar")) {
      throw new IllegalStateException("unexpected checker artifact: " + location);
    }
  }

  record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics,
      String compilerOutput) {

    void assertPass() {
      assertTrue(success, this::text);
    }

    void assertRejected(String checker, String sourceName) {
      assertFalse(success, "invalid nullness contract unexpectedly compiled");
      assertTrue(diagnostics.stream().anyMatch(diagnostic ->
          diagnostic.getKind() == Diagnostic.Kind.ERROR
              && diagnostic.getMessage(Locale.ROOT).contains("[" + checker + "]")
              && diagnostic.getSource() != null
              && diagnostic.getSource().getName().endsWith(sourceName)
              && diagnostic.getLineNumber() > 0
              && diagnostic.getColumnNumber() > 0), this::text);
      assertTrue(diagnostics.stream().filter(diagnostic ->
          diagnostic.getKind() == Diagnostic.Kind.ERROR).allMatch(diagnostic ->
          diagnostic.getMessage(Locale.ROOT).contains("[" + checker + "]")), this::text);
    }

    String text() {
      StringBuilder text = new StringBuilder(compilerOutput);
      for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
        text.append(diagnostic.getKind()).append(':')
            .append(diagnostic.getSource() == null ? "<none>" : diagnostic.getSource().getName())
            .append(':').append(diagnostic.getLineNumber())
            .append(':').append(diagnostic.getColumnNumber()).append(':')
            .append(diagnostic.getMessage(Locale.ROOT)).append('\n');
      }
      return text.toString();
    }
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String content;

    Source(String name, String content) {
      super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
      this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
      return content;
    }
  }
}
