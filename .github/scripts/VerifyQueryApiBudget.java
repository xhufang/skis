import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** CI source-only query API inventory; parse without attribution, processors or class generation. */
class VerifyQueryApiBudget {

  private static final Set<String> GENERATED_CONTRACTS =
      Set.of("io.skis.query.spi.ProjectionMapping", "io.skis.query.spi.ProjectionSource");

  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected the repository root");
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Path queryRoot = Path.of(arguments[0]).resolve("skis-query/src/main/java/io/skis/query");
    try (StandardJavaFileManager files =
            compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        var paths = Files.walk(queryRoot)) {
      verifyTraversal(compiler, files);
      List<Path> sources =
          paths.filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".java"))
              .sorted()
              .toList();
      Set<String> ordinaryTypes =
          publicTypes(compiler, files, files.getJavaFileObjectsFromPaths(sources));
      // The moved validation exception remains conservatively charged to the query budget.
      int count = ordinaryTypes.size() + 1;
      if (count > 30) {
        throw new IllegalStateException(
            "Ordinary query API budget exceeded: " + count + " > 30; " + ordinaryTypes);
      }
      System.out.println("Current query API budget: " + count + "/30");
    }
  }

  private static Set<String> publicTypes(
      JavaCompiler compiler,
      StandardJavaFileManager files,
      Iterable<? extends JavaFileObject> sources)
      throws IOException {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavacTask task =
        (JavacTask)
            compiler.getTask(
                Writer.nullWriter(), files, diagnostics, List.of("-proc:none"), null, sources);
    Set<String> types = new TreeSet<>();
    for (CompilationUnitTree unit : task.parse()) {
      String packageName = unit.getPackageName().toString();
      if (!packageName.equals("io.skis.query") && !packageName.startsWith("io.skis.query.")) {
        throw new IllegalStateException("Unexpected query source package: " + packageName);
      }
      if (List.of(packageName.split("\\.")).contains("internal")) {
        continue;
      }
      for (Tree declaration : unit.getTypeDecls()) {
        collect(declaration, packageName, false, types);
      }
    }
    if (diagnostics.getDiagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
      throw new IllegalStateException(
          "Cannot parse query API sources: " + diagnostics.getDiagnostics());
    }
    return types;
  }

  private static void collect(
      Tree declaration, String owner, boolean implicitPublic, Set<String> types) {
    if (!(declaration instanceof ClassTree type)
        || (!implicitPublic && !type.getModifiers().getFlags().contains(Modifier.PUBLIC))) {
      return;
    }
    String name = owner + "." + type.getSimpleName();
    if (GENERATED_CONTRACTS.contains(name)) {
      return;
    }
    types.add(name);
    boolean publicMembers =
        type.getKind() == Tree.Kind.INTERFACE || type.getKind() == Tree.Kind.ANNOTATION_TYPE;
    for (Tree member : type.getMembers()) {
      collect(member, name, publicMembers, types);
    }
  }

  /** Keep the gate's declaration and visibility rules covered by executable CI fixtures. */
  private static void verifyTraversal(JavaCompiler compiler, StandardJavaFileManager files)
      throws IOException {
    Set<String> actual =
        publicTypes(
            compiler,
            files,
            List.of(
                source(
                    "fixture/Surface",
                    """
                    package io.skis.query.fixture;
                    public class Surface {
                      // public class CommentOnly {}
                      String text = "public class LiteralOnly {}";
                      public static class Visible {}
                      private static class Hidden { public static class Invisible {} }
                      void method() { class Local {} }
                      public interface Implicit {
                        class Nested {}
                        interface Deeper { @interface Marker {} }
                        record Row(int value) {}
                        enum Value { ONE }
                      }
                    }
                    class PackagePrivate { public static class Invisible {} }
                    """),
                source("internal/Hidden", "package io.skis.query.internal; public class Hidden {}"),
                source(
                    "spi/ProjectionMapping",
                    """
                    package io.skis.query.spi;
                    public class ProjectionMapping { public interface Reader {} }
                    """),
                source(
                    "spi/AdditionalSpi", "package io.skis.query.spi; public interface AdditionalSpi {}"),
                source(
                    "QueryRuntime",
                    """
                    package io.skis.query;
                    public class QueryRuntime { public static class NewlyExposed {} }
                    """)));
    Set<String> expected =
        Set.of(
            "io.skis.query.fixture.Surface",
            "io.skis.query.fixture.Surface.Visible",
            "io.skis.query.fixture.Surface.Implicit",
            "io.skis.query.fixture.Surface.Implicit.Nested",
            "io.skis.query.fixture.Surface.Implicit.Deeper",
            "io.skis.query.fixture.Surface.Implicit.Deeper.Marker",
            "io.skis.query.fixture.Surface.Implicit.Row",
            "io.skis.query.fixture.Surface.Implicit.Value",
            "io.skis.query.spi.AdditionalSpi",
            "io.skis.query.QueryRuntime",
            "io.skis.query.QueryRuntime.NewlyExposed");
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Query API inventory fixture mismatch: " + actual);
    }
  }

  private static JavaFileObject source(String name, String content) {
    return new SimpleJavaFileObject(
        URI.create("string:///io/skis/query/" + name + ".java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return content;
      }
    };
  }
}
