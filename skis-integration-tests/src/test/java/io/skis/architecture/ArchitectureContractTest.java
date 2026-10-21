package io.skis.architecture;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.skis.query.spi.ProjectionMapping;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

@EnabledIfSystemProperty(named = "skis.architecture", matches = "true")
public class ArchitectureContractTest {
  private static ModulePolicy policy;
  private static JavaClasses production;
  @TempDir Path temporary;

  @BeforeAll
  static void importActualReactorArtifacts() throws Exception {
    policy = ModulePolicy.load(ModulePolicy.repositoryRoot());
    production = new ClassFileImporter().importPaths(policy.outputs);
    Set<String> imported = new HashSet<>();
    production.forEach(type -> { if (!type.getSimpleName().equals("package-info") && !type.getName().equals("module-info")) imported.add(type.getName()); });
    assertEquals(policy.classOwners.keySet(), imported, "Every registered artifact must actually be imported");
  }

  @Test
  void reactorDependencyAndInternalAccessRulesCoverEveryArtifact() {
    assertEquals(List.of(), ArchitectureRules.dependencies(production, policy));
  }

  @Test
  void exportedSignaturesDoNotLeakInternalTypes() {
    assertEquals(List.of(), ArchitectureRules.publicSurface(production));
  }

  @Test
  void defaultExecutionCompilationBindingAndDecodingAvoidDynamicDiscovery() {
    Set<String> processors = policy.classOwners.entrySet().stream()
        .filter(entry -> entry.getValue().kind().equals("processor"))
        .map(Map.Entry::getKey).collect(Collectors.toSet());
    assertEquals(List.of(), ArchitectureRules.hotPath(production, processors));
  }

  @Test
  void cachedProjectionIdentityReadsDoNotCountAsAssemblyDiscovery() {
    JavaClasses mapping = new ClassFileImporter().importClasses(
        ProjectionMapping.class, ProjectionIdentityReader.class);
    assertEquals(List.of(), ArchitectureRules.hotPath(mapping, Set.of()));
  }

  @Test
  void queryAssemblyHasNoRootPackageAliases() {
    assertTrue(policy.classOwners.containsKey("io.skis.query.internal.QueryRuntime"));
    assertTrue(policy.classOwners.containsKey("io.skis.query.internal.QueryPlanCatalog"));
    assertFalse(policy.classOwners.containsKey("io.skis.query.QueryRuntime"));
    assertFalse(policy.classOwners.containsKey("io.skis.query.QueryPlanCatalog"));
  }

  @Test
  void signatureGenericInheritanceAndReflectionFixturesAreRejected() {
    for (Class<?> fixture : List.of(BadField.class, BadGeneric.class, BadInheritance.class,
        BadProtected.class, BadConstructor.class, BadGenericArray.class, BadThrows.class)) {
      assertFalse(ArchitectureRules.publicSurface(new ClassFileImporter().importClasses(fixture)).isEmpty(), fixture.getName());
    }
    assertFalse(ArchitectureRules.hotPath(new ClassFileImporter().importClasses(BadReflection.class), Set.of()).isEmpty());
    assertFalse(ArchitectureRules.hotPath(new ClassFileImporter().importClasses(BadReflectionReference.class), Set.of()).isEmpty());
    assertFalse(ArchitectureRules.hotPath(new ClassFileImporter().importClasses(BadClassLoader.class), Set.of()).isEmpty());
    assertEquals(List.of(), ArchitectureRules.hotPath(new ClassFileImporter().importClasses(SafeMetadata.class), Set.of()));
  }

  @Test
  void illegalDependenciesAndMissingModulesCannotPass() throws Exception {
    assertTrue(ArchitectureRules.forbiddenRuntime("org.springframework.jdbc.ConnectionHolder", false));
    assertFalse(ArchitectureRules.forbiddenRuntime("org.springframework.jdbc.ConnectionHolder", true));
    assertTrue(ArchitectureRules.forbiddenRuntime("org.postgresql.Driver", false));
    assertTrue(ArchitectureRules.forbiddenRuntime("org.slf4j.simple.SimpleLogger", false));
    assertThrows(IllegalStateException.class, () -> ModulePolicy.requireSameModules(Set.of("known"), Set.of("known", "new-module")));
    assertThrows(IllegalStateException.class, () -> ModulePolicy.requireOutput(temporary.resolve("missing-classes")));
    assertThrows(IllegalStateException.class, () -> ModulePolicy.requireAcyclic(Map.of("a", Set.of("b"), "b", Set.of("a"))));
    JavaClasses bad = new ClassFileImporter().importClasses(BadField.class);
    assertFalse(ArchitectureRules.dependencies(bad, policy).isEmpty(), "Unclassified origins must fail");
    ModulePolicy fixturePolicy = new ModulePolicy(policy.root, policy.modules, policy.internalAccess);
    fixturePolicy.classOwners.putAll(policy.classOwners);
    fixturePolicy.classOwners.put(BadField.class.getName(), policy.modules.get("skis-query"));
    assertTrue(ArchitectureRules.dependencies(bad, fixturePolicy).stream()
        .anyMatch(error -> error.startsWith("Unapproved internal dependency:")));
    Set<ModulePolicy.Access> permitted = new HashSet<>(policy.internalAccess);
    permitted.add(new ModulePolicy.Access(
        BadField.class.getName(), "io.skis.jdbc.internal.JdbcExecutor"));
    ModulePolicy permittedPolicy = new ModulePolicy(policy.root, policy.modules, permitted);
    permittedPolicy.classOwners.putAll(fixturePolicy.classOwners);
    assertEquals(List.of(), ArchitectureRules.dependencies(bad, permittedPolicy));
    permittedPolicy.generated.add(ArchitectureContractTest.class.getName());
    assertTrue(ArchitectureRules.dependencies(bad, permittedPolicy).stream()
        .anyMatch(error -> error.startsWith("Generated ")));
  }

  @Test
  void registeredProductionModulesCannotOmitRuntimeEnforcer() throws Exception {
    ModulePolicy.Module registered = new ModulePolicy.Module(
        "skis-new-production", "skis-new-production", "production", Set.of("skis-annotations"));
    Map<String, ModulePolicy.Module> modules = new LinkedHashMap<>(policy.modules);
    modules.put(registered.path(), registered);
    ModulePolicy fixturePolicy = new ModulePolicy(policy.root, modules, policy.internalAccess);
    // The new module has the same dependency closure as core, so its valid configuration can be reused.
    Element configured = ModulePolicy.readPom(policy.root.resolve("skis-core/pom.xml"));
    assertDoesNotThrow(() -> fixturePolicy.requireRuntimeEnforcer(registered, configured));

    Element missingPlugin = (Element) configured.cloneNode(true);
    Element plugin = element(missingPlugin, "plugin");
    plugin.getParentNode().removeChild(plugin);
    assertThrows(IllegalStateException.class,
        () -> fixturePolicy.requireRuntimeEnforcer(registered, missingPlugin));

    Element missingExecution = (Element) configured.cloneNode(true);
    Element execution = element(missingExecution, "execution");
    execution.getParentNode().removeChild(execution);
    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> fixturePolicy.requireRuntimeEnforcer(registered, missingExecution));
    assertTrue(failure.getMessage().contains(registered.artifact()));

    Element managedOnly = (Element) configured.cloneNode(true);
    Element build = element(managedOnly, "build");
    Element management = managedOnly.getOwnerDocument().createElement("pluginManagement");
    management.appendChild(element(build, "plugins"));
    build.appendChild(management);
    assertThrows(IllegalStateException.class,
        () -> fixturePolicy.requireRuntimeEnforcer(registered, managedOnly));
  }

  @Test
  void runtimeEnforcerMustRemainActiveAndCheckTransitiveDependencies() throws Exception {
    ModulePolicy.Module core = policy.modules.get("skis-core");
    Element configured = ModulePolicy.readPom(policy.root.resolve("skis-core/pom.xml"));
    for (String owner : List.of("plugin", "execution")) {
      for (var setting : Map.of("skip", "true", "fail", "false",
          "rulesToSkip", "bannedDependencies", "rulesToExecute", "requireJavaVersion").entrySet()) {
        Element changed = (Element) configured.cloneNode(true);
        Element parent = element(changed, owner);
        Element configuration;
        if (owner.equals("plugin")) {
          configuration = changed.getOwnerDocument().createElement("configuration");
          parent.appendChild(configuration);
        } else {
          configuration = element(parent, "configuration");
        }
        appendText(configuration, setting.getKey(), setting.getValue());
        assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, changed),
            owner + " configuration: " + setting.getKey());
      }
    }
    Element noLifecycle = (Element) configured.cloneNode(true);
    appendText(element(noLifecycle, "execution"), "phase", "none");
    assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, noLifecycle));
    Element wrongGoal = (Element) configured.cloneNode(true);
    element(wrongGoal, "goal").setTextContent("help");
    assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, wrongGoal));
    Element noTransitive = (Element) configured.cloneNode(true);
    element(noTransitive, "searchTransitive").setTextContent("false");
    assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, noTransitive));
    Element warningOnly = (Element) configured.cloneNode(true);
    appendText(element(warningOnly, "bannedDependencies"), "level", "WARN");
    assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, warningOnly));
  }

  @Test
  void runtimeEnforcerAllowlistMustMatchTheRegisteredDependencyClosure() throws Exception {
    ModulePolicy.Module core = policy.modules.get("skis-core");
    Element configured = ModulePolicy.readPom(policy.root.resolve("skis-core/pom.xml"));
    Element noDefaultBan = (Element) configured.cloneNode(true);
    Element excluded = element(noDefaultBan, "exclude");
    excluded.getParentNode().removeChild(excluded);
    assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, noDefaultBan));
    Element missingDependency = (Element) configured.cloneNode(true);
    Element included = element(missingDependency, "include");
    included.getParentNode().removeChild(included);
    assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, missingDependency));
    for (String extra : List.of("org.postgresql:postgresql", "*:*")) {
      Element widened = (Element) configured.cloneNode(true);
      appendText(element(widened, "includes"), "include", extra);
      assertThrows(IllegalStateException.class, () -> policy.requireRuntimeEnforcer(core, widened), extra);
    }
  }

  private static Element element(Element parent, String name) {
    return assertInstanceOf(Element.class, parent.getElementsByTagName(name).item(0), name);
  }

  private static void appendText(Element parent, String name, String value) {
    Element child = parent.getOwnerDocument().createElement(name);
    child.setTextContent(value);
    parent.appendChild(child);
  }

  public static class BadField {
    public io.skis.jdbc.internal.JdbcExecutor executor;
  }
  public static class BadGeneric<T extends io.skis.jdbc.internal.JdbcExecutor> {}
  public static class BadInheritance extends io.skis.architecture.fixtures.internal.HiddenBase {}
  public static class BadProtected {
    protected List<io.skis.jdbc.internal.JdbcExecutor> leak() { return List.of(); }
  }
  public static class BadConstructor {
    protected BadConstructor(io.skis.jdbc.internal.JdbcExecutor executor) {}
  }
  public static class BadGenericArray {
    public List<io.skis.jdbc.internal.JdbcExecutor[]> executors;
  }
  public static class BadThrows {
    public void leak() throws io.skis.architecture.fixtures.internal.HiddenFailure {}
  }
  static class BadReflection {
    Object fields(Class<?> type) { return type.getDeclaredFields(); }
    Class<?> load(String name) throws ClassNotFoundException { return Class.forName(name); }
  }
  static class SafeMetadata {
    String name(Class<?> type) { return type.getName(); }
  }
  static class ProjectionIdentityReader {
    long token(ProjectionMapping<?> mapping) { return mapping.statefulDecoderToken(); }
    java.util.function.LongSupplier tokenReference(ProjectionMapping<?> mapping) {
      return mapping::statefulDecoderToken;
    }
  }
  static class BadClassLoader extends ClassLoader {
    Class<?> define(byte[] code) { return defineClass(null, code, 0, code.length); }
  }
  static class BadReflectionReference {
    java.util.function.Function<Class<?>, java.lang.reflect.Field[]> discovery() {
      return Class::getDeclaredFields;
    }
  }
}
