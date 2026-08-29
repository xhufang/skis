package io.skis.processor;

/** Keeps compile-time diagnostics actionable without exposing a new processor API. */
final class DiagnosticGuidance {

  private DiagnosticGuidance() {}

  static String format(String code, String message) {
    return "[" + code + "] " + message + ". Fix: " + fix(code);
  }

  private static String fix(String code) {
    return switch (code) {
      case "SKIS001" -> "add @SkisEntity or remove the type from entity processing";
      case "SKIS002" -> "move the entity to a top-level declaration";
      case "SKIS003" -> "declare the entity public";
      case "SKIS004" -> "remove entity type parameters";
      case "SKIS005" -> "declare the entity in a named Java package";
      case "SKIS007" -> "remove @Transient or remove the conflicting @Id/@Version role";
      case "SKIS008" -> "remove explicit nullable=true from the @Id column";
      case "SKIS009" -> "keep only one @Version property";
      case "SKIS010" -> "use a supported integral, BigInteger, or BigDecimal version type";
      case "SKIS011" -> "remove explicit nullable=true from the @Version column";
      case "SKIS012" -> "keep one writable property for the physical column";
      case "SKIS013" -> "declare at least one persistent property";
      case "SKIS014" -> "declare one @Id property or mark the entity read-only";
      case "SKIS015" -> "remove @Version from the read-only entity";
      case "SKIS016" -> "rename properties whose generated metadata constants collide";
      case "SKIS019" -> "replace whitespace-only table or column names with valid names";
      case "SKIS020" -> "keep one @Id until explicit composite-key declarations are supported";
      case "SKIS021" -> "rename properties whose generated table methods collide";
      case "SKIS022" -> "use a Java type with a built-in JDBC codec";
      case "SKIS023" -> "mark the primitive column non-null or use its wrapper type";
      case "SKIS024" -> "use separate properties for @Id and @Version";
      case "SKIS025" -> "make the record component accessor publicly accessible";
      case "SKIS026" -> "set @Column.length to zero or a positive value";
      case "SKIS027" -> "set @Column.precision to zero or a positive value";
      case "SKIS028" -> "set @Column.scale to zero or a positive value";
      case "SKIS029" -> "make scale less than or equal to precision";
      case "SKIS030" -> "make the numeric version column insertable and updatable";
      case "SKIS031" -> "use a public record or non-abstract concrete class";
      case "SKIS032" -> "remove Simple Entity inheritance";
      case "SKIS033" -> "provide a public no-args constructor";
      case "SKIS034" -> "provide a public getter, fluent reader, or public field";
      case "SKIS035" -> "provide a public setter, fluent writer, or writable public field";
      case "SKIS036" -> "make field and getter @Column declarations identical";
      case "SKIS037" -> "make accessor types exactly match the property type";
      case "SKIS038" ->
          "enable Lombok annotation processing and use a supported mutable Bean shape";
      case "SKIS039" -> "remove unsupported checked exceptions from the no-args constructor";
      case "SKIS040" -> "remove unsupported checked exceptions from Bean accessors";
      case "SKIS097" -> "generate referenced entity property types before processing ends";
      case "SKIS098" -> "remove entity-index output conflicts and check the class output path";
      case "SKIS099" -> "remove generated entity source conflicts and check the source output path";
      case "SKIS201" -> "add @SkisProjection or remove the type from projection processing";
      case "SKIS202" -> "use a projection class or record";
      case "SKIS203" -> "move the projection to a top-level declaration";
      case "SKIS204" -> "declare the projection public";
      case "SKIS205" -> "remove projection type parameters";
      case "SKIS206" -> "declare the projection in a named Java package";
      case "SKIS207" -> "select a public projection constructor";
      case "SKIS208" -> "declare at least one projection constructor parameter";
      case "SKIS209" -> "replace the variable-arity constructor with fixed parameters";
      case "SKIS210" -> "remove the projection constructor throws clause";
      case "SKIS211" -> "keep only one @ProjectionConstructor";
      case "SKIS212" -> "keep one public constructor or mark one explicitly";
      case "SKIS213" -> "use the record canonical constructor";
      case "SKIS214" -> "use a concrete projection class";
      case "SKIS215" -> "move @ProjectionConstructor to the record canonical constructor";
      case "SKIS216" -> "remove projection constructor type parameters";
      case "SKIS217" ->
          "generate referenced types and enable Lombok processing before processing ends";
      case "SKIS218" -> "make every projection parameter type accessible from the .skis package";
      case "SKIS219" -> "set @SkisProjection.entity to a declared @SkisEntity type";
      case "SKIS220" -> "fix the nested entity diagnostic before generating the projection";
      case "SKIS221" -> "match an entity property name or add @ProjectionProperty";
      case "SKIS222" -> "make the projection parameter and entity property types match";
      case "SKIS223" -> "use a wrapper parameter or make the selected property non-null";
      case "SKIS224" -> "name a persistent entity property in @ProjectionProperty";
      case "SKIS298" -> "remove projection-index output conflicts and check the class output path";
      case "SKIS299" ->
          "remove generated projection source conflicts and check the source output path";
      default -> "see the SKIS APT error guide for this diagnostic";
    };
  }
}
