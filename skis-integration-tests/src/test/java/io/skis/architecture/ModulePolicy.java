package io.skis.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Discovers the actual reactor, then checks its committed classification and direct graph. */
final class ModulePolicy {
  record Module(String path, String artifact, String kind, Set<String> dependencies) {}
  record Access(String caller, String target) {}

  final Path root;
  final Map<String, Module> modules;
  final Map<String, Module> classOwners = new LinkedHashMap<>();
  final Set<Access> internalAccess;
  final List<Path> outputs = new ArrayList<>();
  final Set<String> generated = new HashSet<>();

  ModulePolicy(Path root, Map<String, Module> modules, Set<Access> access) {
    this.root = root;
    this.modules = Map.copyOf(modules);
    this.internalAccess = Set.copyOf(access);
  }

  static Path repositoryRoot() {
    Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (path != null && !Files.isRegularFile(path.resolve(".github/quality/modules.json"))) {
      path = path.getParent();
    }
    if (path == null) throw new IllegalStateException("Cannot locate repository quality policy");
    return path;
  }

  static ModulePolicy load(Path root) throws Exception {
    Map<String, Module> modules = new LinkedHashMap<>();
    Map<String, Object> document = QualityJson.read(root.resolve(".github/quality/modules.json"));
    if (!"1".equals(document.get("schema"))) throw new IllegalStateException("Unknown module schema");
    for (Object item : QualityJson.array(document.get("modules"))) {
      Map<String, Object> value = QualityJson.object(item);
      Set<String> dependencies = QualityJson.array(value.get("dependencies")).stream()
          .map(QualityJson::string).collect(Collectors.toUnmodifiableSet());
      Module module = new Module(QualityJson.string(value.get("path")),
          QualityJson.string(value.get("artifact")), QualityJson.string(value.get("kind")), dependencies);
      if (modules.putIfAbsent(module.path(), module) != null) throw new IllegalStateException("Duplicate module");
    }
    Set<Access> accesses = new HashSet<>();
    Map<String, Object> accessDocument = QualityJson.read(root.resolve(".github/quality/internal-access.json"));
    if (!"1".equals(accessDocument.get("schema"))) throw new IllegalStateException("Unknown access schema");
    for (Object item : QualityJson.array(accessDocument.get("accesses"))) {
      Map<String, Object> value = QualityJson.object(item);
      for (String field : List.of("reason", "owner", "exit")) QualityJson.string(value.get(field));
      Access access = new Access(QualityJson.string(value.get("caller")), QualityJson.string(value.get("target")));
      if (!accesses.add(access) || access.caller().contains("*") || access.target().contains("*")) {
        throw new IllegalStateException("Duplicate or wildcard internal permission: " + access);
      }
    }
    ModulePolicy policy = new ModulePolicy(root, modules, accesses);
    Map<String, Element> reactor = new LinkedHashMap<>();
    policy.discover(".", reactor);
    requireSameModules(modules.keySet(), reactor.keySet());
    Set<String> artifacts = new HashSet<>();
    for (Module module : modules.values()) {
      if (!artifacts.add(module.artifact())) throw new IllegalStateException("Duplicate artifact: " + module);
      Element pom = reactor.get(module.path());
      if (!module.artifact().equals(text(pom, "artifactId"))) throw new IllegalStateException("Wrong artifact: " + module);
      Set<String> actualDependencies = new HashSet<>();
      for (Element container : children(pom, "dependencies")) {
        for (Element dependency : children(container, "dependency")) {
          String scope = text(dependency, "scope");
          if ("io.github.xhufang".equals(text(dependency, "groupId"))
              && !Set.of("test", "provided").contains(scope)) {
            actualDependencies.add(text(dependency, "artifactId"));
          }
        }
      }
      if (!module.dependencies().equals(actualDependencies)) {
        throw new IllegalStateException("Unregistered direct dependency in " + module.artifact()
            + ": expected " + module.dependencies() + ", actual " + actualDependencies);
      }
    }
    Map<String, Set<String>> graph = modules.values().stream()
        .collect(Collectors.toMap(Module::artifact, Module::dependencies));
    requireAcyclic(graph);
    for (Module module : modules.values()) {
      policy.requireRuntimeEnforcer(module, reactor.get(module.path()));
      policy.inventory(module);
    }
    for (Access access : accesses) {
      if (!policy.classOwners.containsKey(access.caller()) || !policy.classOwners.containsKey(access.target())) {
        throw new IllegalStateException("Internal permission names a missing type: " + access);
      }
    }
    return policy;
  }

  /** Every registered production module must explicitly enforce the runtime dependency closure. */
  void requireRuntimeEnforcer(Module module, Element project) {
    if (!module.kind().equals("production")) return;
    List<Element> plugins = children(project, "build").stream()
        .flatMap(build -> children(build, "plugins").stream())
        .flatMap(container -> children(container, "plugin").stream())
        .filter(plugin -> text(plugin, "artifactId").equals("maven-enforcer-plugin")
            && Set.of("", "org.apache.maven.plugins").contains(text(plugin, "groupId")))
        .toList();
    if (plugins.size() != 1) {
      throw new IllegalStateException("Missing or duplicate runtime Enforcer plugin in " + module.artifact());
    }
    Element plugin = plugins.getFirst();
    children(plugin, "configuration").forEach(configuration -> requireEnabledEnforcer(module, configuration));
    List<Element> executions = children(plugin, "executions").stream()
        .flatMap(container -> children(container, "execution").stream())
        .filter(execution -> text(execution, "id").equals("enforce-runtime-boundaries"))
        .toList();
    if (executions.size() != 1) {
      throw new IllegalStateException("Missing or duplicate enforce-runtime-boundaries in " + module.artifact());
    }
    Element execution = executions.getFirst();
    if (!Set.of("", "validate").contains(text(execution, "phase"))
        || !values(execution, "goals", "goal").equals(Set.of("enforce"))) {
      throw new IllegalStateException("Runtime Enforcer must execute during validate in " + module.artifact());
    }
    Element configuration = singleChild(execution, "configuration");
    requireEnabledEnforcer(module, configuration);
    Element rule = singleChild(singleChild(configuration, "rules"), "bannedDependencies");
    if (!text(rule, "searchTransitive").equals("true")
        || !Set.of("", "ERROR").contains(text(rule, "level"))
        || !values(rule, "excludes", "exclude").contains("*:*")
        || !values(rule, "includes", "include").equals(runtimeIncludes(module))) {
      throw new IllegalStateException("Runtime Enforcer dependency policy differs from module policy in " + module.artifact());
    }
  }

  private Set<String> runtimeIncludes(Module module) {
    Set<String> includes = new HashSet<>(Set.of("org.jspecify:jspecify", "*:*:*:*:test"));
    if (!module.artifact().equals("skis-annotations")) includes.add("org.slf4j:slf4j-api");
    for (Module dependency : modules.values()) {
      if (!dependency.artifact().equals(module.artifact()) && permitsDependency(module, dependency)) {
        includes.add("io.github.xhufang:" + dependency.artifact());
      }
    }
    if (module.artifact().equals("skis-spring")) {
      includes.addAll(Set.of("org.springframework:spring-jdbc", "org.springframework:spring-tx",
          "org.springframework:spring-core", "org.springframework:spring-beans", "commons-logging:commons-logging"));
    }
    return includes;
  }

  private static void requireEnabledEnforcer(Module module, Element configuration) {
    if (!Set.of("", "false").contains(text(configuration, "skip"))
        || !Set.of("", "true").contains(text(configuration, "fail"))
        || !text(configuration, "rulesToSkip").isEmpty()
        || !text(configuration, "rulesToExecute").isEmpty()) {
      throw new IllegalStateException("Runtime Enforcer cannot be disabled or filtered in " + module.artifact());
    }
  }

  private static Set<String> values(Element parent, String container, String item) {
    return children(parent, container).stream()
        .flatMap(element -> children(element, item).stream())
        .map(element -> element.getTextContent().strip()).collect(Collectors.toUnmodifiableSet());
  }

  private static Element singleChild(Element parent, String name) {
    List<Element> matches = children(parent, name);
    if (matches.size() != 1) throw new IllegalStateException("Expected one " + name + " under " + parent.getTagName());
    return matches.getFirst();
  }

  static void requireSameModules(Set<String> registered, Set<String> discovered) {
    if (!registered.equals(discovered)) {
      throw new IllegalStateException("Reactor classification mismatch; registered=" + registered + ", discovered=" + discovered);
    }
  }

  static void requireAcyclic(Map<String, Set<String>> graph) {
    Set<String> done = new HashSet<>();
    for (String module : graph.keySet()) visit(module, graph, done, new HashSet<>());
  }

  private static void visit(String module, Map<String, Set<String>> graph, Set<String> done, Set<String> active) {
    if (done.contains(module)) return;
    if (!active.add(module)) throw new IllegalStateException("Module dependency cycle at " + module);
    if (!graph.containsKey(module)) throw new IllegalStateException("Unknown module dependency " + module);
    for (String target : graph.get(module)) visit(target, graph, done, active);
    active.remove(module);
    done.add(module);
  }

  boolean permitsDependency(Module source, Module target) {
    return source.artifact().equals(target.artifact()) || reachable(source.artifact(), target.artifact(), new HashSet<>());
  }

  private boolean reachable(String source, String target, Set<String> seen) {
    if (!seen.add(source)) return false;
    Module module = modules.values().stream().filter(m -> m.artifact().equals(source)).findFirst().orElseThrow();
    return module.dependencies().contains(target)
        || module.dependencies().stream().anyMatch(next -> reachable(next, target, seen));
  }

  private void inventory(Module module) throws IOException {
    if (!Set.of("production", "processor", "fixture", "application", "test-suite", "frozen", "aggregator").contains(module.kind())) {
      throw new IllegalStateException("Unclassified module: " + module);
    }
    Path directory = root.resolve(module.path()).normalize();
    if (!directory.startsWith(root)) throw new IllegalStateException("Module outside reactor: " + module.path());
    Path source = directory.resolve("src/main/java");
    boolean hasSource = false;
    if (Files.isDirectory(source)) {
      try (var paths = Files.walk(source)) {
        hasSource = paths.anyMatch(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("package-info.java"));
      }
    }
    boolean expectsSource = Set.of("production", "processor", "fixture", "application").contains(module.kind());
    if (expectsSource != hasSource) throw new IllegalStateException("Module source classification changed: " + module);
    if (!expectsSource) return;
    Path output = directory.resolve("target/classes");
    requireOutput(output);
    try (var paths = Files.walk(source)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".java")
          && !p.getFileName().toString().equals("package-info.java")
          && !p.getFileName().toString().equals("module-info.java")).toList()) {
        var packageName = java.util.regex.Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(Files.readString(file));
        if (!packageName.find()) throw new IllegalStateException("Unclassified source package: " + file);
        Path expected = output.resolve(packageName.group(1).replace('.', '/'))
            .resolve(file.getFileName().toString().replaceAll("\\.java$", ".class"));
        if (!Files.isRegularFile(expected)) throw new IllegalStateException("Source omitted from production artifact: " + file);
      }
    }
    outputs.add(output);
    try (var paths = Files.walk(output)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
        String name = output.relativize(file).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
        if (name.equals("module-info") || name.endsWith("package-info")) continue;
        if (classOwners.putIfAbsent(name, module) != null) throw new IllegalStateException("Duplicate bytecode class: " + name);
      }
    }
    Path generatedSources = directory.resolve("target/generated-sources");
    if (Files.isDirectory(generatedSources)) {
      try (var paths = Files.walk(generatedSources)) {
        for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
          String code = Files.readString(file);
          if (code.matches("(?s).*io\\.skis\\.[\\w.]*\\binternal\\b.*")) {
            throw new IllegalStateException("Generated source references internal: " + file);
          }
          var pkg = java.util.regex.Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(code);
          if (!pkg.find()) throw new IllegalStateException("Generated source has no package: " + file);
          String name = pkg.group(1) + "." + file.getFileName().toString().replaceAll("\\.java$", "");
          if (name.endsWith("package-info") || name.equals("module-info")) continue;
          if (!classOwners.containsKey(name)) {
            throw new IllegalStateException("Generated source omitted from production artifact: " + file);
          }
          generated.add(name);
        }
      }
    }
  }

  static void requireOutput(Path output) throws IOException {
    if (!Files.isDirectory(output)) throw new IllegalStateException("Missing production bytecode: " + output);
    try (var paths = Files.walk(output)) {
      if (paths.noneMatch(p -> p.toString().endsWith(".class"))) throw new IllegalStateException("Empty production bytecode: " + output);
    }
  }

  private void discover(String relative, Map<String, Element> result) throws Exception {
    Path pom = root.resolve(relative).resolve("pom.xml").normalize();
    if (!pom.startsWith(root)) throw new IllegalStateException("Reactor path escaped repository");
    Element project = readPom(pom);
    if (project.getElementsByTagName("modules").getLength() != children(project, "modules").size()) {
      throw new IllegalStateException("Profile-specific reactor modules require explicit quality-policy support: " + pom);
    }
    if (result.putIfAbsent(relative, project) != null) throw new IllegalStateException("Repeated reactor module " + relative);
    for (Element modules : children(project, "modules")) {
      for (Element child : children(modules, "module")) {
        Path next = Path.of(relative).resolve(child.getTextContent().strip()).normalize();
        discover(next.toString().replace('\\', '/'), result);
      }
    }
  }

  static Element readPom(Path pom) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && name.equals(element.getTagName())) result.add(element);
    }
    return result;
  }

  private static String text(Element parent, String name) {
    List<Element> values = children(parent, name);
    return values.isEmpty() ? "" : values.getFirst().getTextContent().strip();
  }
}
