package io.skis.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Bytecode rules shared by actual artifacts and intentionally invalid test classes. */
final class ArchitectureRules {
  private ArchitectureRules() {}

  static List<String> publicSurface(JavaClasses classes) {
    List<String> errors = new ArrayList<>();
    for (JavaClass type : classes) {
      if (internal(type) || !exported(type)) continue;
      type.getSuperclass().ifPresent(parent -> signature(type.getName(), parent, errors));
      type.getInterfaces().forEach(parent -> signature(type.getName(), parent, errors));
      type.getTypeParameters().forEach(parameter -> signature(type.getName(), parameter, errors));
      type.getAllFields().stream().filter(ArchitectureRules::visible)
          .forEach(field -> signature(field.getFullName(), field.getType(), errors));
      for (JavaCodeUnit member : type.getCodeUnits()) {
        if (!visible(member)) continue;
        member.getAllInvolvedRawTypes().stream().filter(ArchitectureRules::internal)
            .forEach(target -> errors.add(member.getFullName() + " leaks " + target.getName()));
        member.getExceptionTypes().stream().filter(ArchitectureRules::internal)
            .forEach(target -> errors.add(member.getFullName() + " leaks " + target.getName()));
      }
    }
    return errors;
  }

  private static void signature(String source, JavaType type, List<String> errors) {
    type.getAllInvolvedRawTypes().stream().filter(ArchitectureRules::internal)
        .forEach(target -> errors.add(source + " leaks " + target.getName()));
  }

  private static boolean exported(JavaClass type) {
    if (!type.getModifiers().contains(JavaModifier.PUBLIC)
        && !type.getModifiers().contains(JavaModifier.PROTECTED)) return false;
    return type.getEnclosingClass().map(ArchitectureRules::exported).orElse(true);
  }

  private static boolean visible(JavaMember member) {
    return member.getModifiers().contains(JavaModifier.PUBLIC)
        || member.getModifiers().contains(JavaModifier.PROTECTED);
  }

  static boolean internal(JavaClass type) {
    return type.getBaseComponentType().getPackageName().contains(".internal.")
        || type.getBaseComponentType().getPackageName().endsWith(".internal");
  }

  static List<String> dependencies(JavaClasses classes, ModulePolicy policy) {
    List<String> errors = new ArrayList<>();
    for (JavaClass type : classes) {
      if (type.getName().endsWith("package-info") || type.getName().equals("module-info")) continue;
      ModulePolicy.Module source = policy.classOwners.get(type.getName());
      if (source == null) { errors.add("Uninventoried imported class " + type.getName()); continue; }
      for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
        JavaClass targetType = dependency.getTargetClass().getBaseComponentType();
        ModulePolicy.Module target = policy.classOwners.get(targetType.getName());
        if (source.kind().equals("production") && forbiddenRuntime(targetType.getName(), source.artifact().startsWith("skis-spring"))) {
          errors.add(dependency.getDescription());
        }
        if (target != null && !policy.permitsDependency(source, target)) errors.add(dependency.getDescription());
        if (internal(targetType) && (target == null || !target.artifact().equals(source.artifact()))
            && !policy.internalAccess.contains(new ModulePolicy.Access(type.getName(), targetType.getName()))) {
          errors.add("Unapproved internal dependency: " + dependency.getDescription());
        }
        String outer = type.getName().split("\\$")[0];
        if (policy.generated.contains(outer) && internal(targetType)) errors.add("Generated " + dependency.getDescription());
      }
    }
    return errors;
  }

  static boolean forbiddenRuntime(String name, boolean springIntegration) {
    return (!springIntegration && (name.startsWith("org.springframework.") || name.startsWith("org.springframework.boot.")))
        || List.of("org.mybatis.", "org.apache.ibatis.", "com.baomidou.", "org.hibernate.",
            "org.babyfish.jimmer.", "jakarta.persistence.", "javax.persistence.", "org.postgresql.",
            "org.h2.", "com.mysql.", "org.mariadb.jdbc.", "com.microsoft.sqlserver.", "oracle.jdbc.",
            "org.sqlite.", "com.ibm.db2.", "ch.qos.logback.", "org.apache.logging.log4j.core.",
            "org.apache.logging.slf4j.", "org.slf4j.simple.", "org.slf4j.jul.", "org.slf4j.reload4j.",
            "com.github.benmanes.caffeine.", "redis.clients.", "io.lettuce.", "io.skis.processor.")
            .stream().anyMatch(name::startsWith);
  }

  static List<String> hotPath(JavaClasses classes, Set<String> processorClasses) {
    List<String> errors = new ArrayList<>();
    for (JavaClass type : classes) {
      if (processorClasses.contains(type.getName())) continue;
      for (JavaAccess<?> access : type.getAccessesFromSelf()) {
        String owner = access.getTargetOwner().getName();
        String member = access.getTarget().getName();
        if (forbiddenOperation(access.getTargetOwner(), member) && !assemblyException(access)) errors.add(access.getDescription());
        String requiredCaller = assemblyCaller(owner + "#" + member);
        String actualCaller = access.getOriginOwner().getName() + "#" + access.getOrigin().getName();
        if (requiredCaller != null && !requiredCaller.equals(actualCaller)) {
          errors.add("Assembly discovery reached outside its registered entry: " + access.getDescription());
        }
      }
      for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
        String target = dependency.getTargetClass().getName();
        if (List.of("net.sf.jsqlparser.", "org.apache.calcite.sql.parser.", "com.alibaba.druid.sql.parser.")
            .stream().anyMatch(target::startsWith)) errors.add(dependency.getDescription());
      }
    }
    return errors;
  }

  private static boolean forbiddenOperation(JavaClass targetOwner, String member) {
    String owner = targetOwner.getName();
    return (owner.startsWith("java.lang.reflect.") && !owner.endsWith("Exception") && !owner.equals("java.lang.reflect.Modifier"))
        || (owner.equals("java.lang.Class") && (member.startsWith("getDeclared") || member.startsWith("getAnnotated")
            || Set.of("forName", "newInstance", "getField", "getFields", "getMethod", "getMethods",
                "getConstructor", "getConstructors", "getAnnotation", "getAnnotations",
                "getAnnotationsByType", "isAnnotationPresent", "getRecordComponents", "getEnumConstants",
                "getEnclosingMethod", "getEnclosingConstructor", "getResource", "getResourceAsStream").contains(member)))
        || (targetOwner.isAssignableTo(ClassLoader.class) && Set.of("loadClass", "findClass", "defineClass",
            "getResources", "resources", "findResources", "getResource", "findResource", "getResourceAsStream").contains(member))
        || (owner.equals("java.util.ServiceLoader") && member.startsWith("load"))
        || (owner.equals("java.lang.invoke.MethodHandles") && Set.of("lookup", "publicLookup", "privateLookupIn").contains(member))
        || (owner.equals("java.lang.invoke.MethodHandles$Lookup") && (member.startsWith("find") || member.startsWith("unreflect") || member.startsWith("define") || member.equals("bind")))
        || (owner.equals("java.lang.invoke.MethodHandle") && member.startsWith("invoke"))
        || owner.equals("java.lang.invoke.VarHandle");
  }

  private static boolean assemblyException(JavaAccess<?> access) {
    String caller = access.getOriginOwner().getName() + "#" + access.getOrigin().getName();
    String target = access.getTargetOwner().getName() + "#" + access.getTarget().getName();
    return switch (caller) {
      case "io.skis.runtime.GeneratedIndexReader#resources" -> target.equals("java.lang.ClassLoader#getResources");
      case "io.skis.runtime.EntityRuntimeModelLoader#loadProvider" -> Set.of("java.lang.Class#forName",
          "java.lang.Class#getConstructor", "java.lang.reflect.Constructor#newInstance").contains(target);
      case "io.skis.query.spi.ProjectionMapping#hasInstanceState" -> Set.of("java.lang.Class#getDeclaredFields",
          "java.lang.reflect.Field#getModifiers").contains(target);
      default -> false;
    };
  }

  /** T03 exceptions remain restricted to generated-index/provider assembly or mapping identity. */
  private static String assemblyCaller(String member) {
    return switch (member) {
      case "io.skis.runtime.GeneratedIndexReader#resources" -> "io.skis.runtime.GeneratedIndexReader#read";
      case "io.skis.runtime.GeneratedIndexReader#read", "io.skis.runtime.EntityRuntimeModelLoader#loadProvider" -> "io.skis.runtime.EntityRuntimeModelLoader#load";
      case "io.skis.runtime.EntityRuntimeModelLoader#load" -> "io.skis.runtime.Skis$Builder#build";
      case "io.skis.query.spi.ProjectionMapping#hasInstanceState" -> "io.skis.query.spi.ProjectionMapping#statefulDecoderToken";
      case "io.skis.query.spi.ProjectionMapping#statefulDecoderToken" -> "io.skis.query.spi.ProjectionMapping#<init>";
      default -> null;
    };
  }
}
