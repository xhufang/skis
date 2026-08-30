# SKIS annotation-processing error guide

SKIS reports compile-time mapping failures as stable `SKISxxx` codes. The diagnostic points at the
relevant source element, explains the failure, and includes a short `Fix:` hint. This guide provides
the longer contract, invalid and valid examples, and the first public version of every code.

All codes below are part of the public diagnostic contract since `0.1.0` unless a section explicitly
says that it is historical. `SKIS017` and `SKIS018` are intentionally unassigned. A code is never
reused for another meaning. Internal `0.1.1` through `0.1.6` hardening keeps these numbers and
meanings stable; those milestones are not separately published versions.

## Entity declaration and mapping

### SKIS001

- Cause: an entity scan was requested for a type without `@SkisEntity`.
- Invalid: `public record Pet(Long id) {}`.
- Valid: `@SkisEntity public record Pet(@Id Long id) {}`.
- Fix: add `@SkisEntity`, or do not submit the type to the entity processor.
- First public version: `0.1.0`.

### SKIS002

- Cause: the entity is nested in another type.
- Invalid: `class Models { @SkisEntity public record Pet(Long id) {} }`.
- Valid: declare `Pet` in its own top-level `Pet.java` file.
- Fix: move the entity to a top-level declaration.
- First public version: `0.1.0`.

### SKIS003

- Cause: generated classes in the `.skis` subpackage cannot access a non-public entity.
- Invalid: `@SkisEntity record Pet(@Id Long id) {}`.
- Valid: `@SkisEntity public record Pet(@Id Long id) {}`.
- Fix: declare the entity `public`.
- First public version: `0.1.0`.

### SKIS004

- Cause: Simple Entity types cannot declare type parameters.
- Invalid: `@SkisEntity public class Pet<T> { ... }`.
- Valid: use a concrete property type, for example `public class Pet { private String value; ... }`.
- Fix: remove the entity type parameters.
- First public version: `0.1.0`.

### SKIS005

- Cause: the entity is in the unnamed Java package.
- Invalid: an annotated `Pet` source with no `package` declaration.
- Valid: start the source with `package example.model;`.
- Fix: move the entity into a named package.
- First public version: `0.1.0`.

### SKIS006

- Cause: historical SKIS builds accepted only record entities and used this code for classes.
- Invalid historical shape: a class entity compiled with a record-only processor.
- Valid now: a supported public record or mutable Bean Simple Entity.
- Fix: no current processor path emits this code; it remains reserved for compatibility.
- First public version: historical pre-`0.1.0`; reserved in `0.1.0`.

### SKIS007

- Cause: one property combines `@Transient` with `@Id` or `@Version`.
- Invalid: `@Transient @Id Long id`.
- Valid: `@Id Long id`, or `@Transient String displayLabel`.
- Fix: keep either the persistent identity/version role or the transient role, not both.
- First public version: `0.1.0`.

### SKIS008

- Cause: an `@Id` explicitly declares `@Column(nullable = true)`.
- Invalid: `@Id @Column(nullable = true) Long id`.
- Valid: `@Id Long id` or `@Id @Column(nullable = false) Long id`.
- Fix: remove `nullable = true`; identifier columns are implicitly non-null.
- First public version: `0.1.0`.

### SKIS009

- Cause: the entity declares more than one `@Version` property.
- Invalid: `@Version Long rowVersion; @Version Long auditVersion;`.
- Valid: keep one `@Version Long version` property.
- Fix: choose a single optimistic-lock version property.
- First public version: `0.1.0`.

### SKIS010

- Cause: `NUMERIC_INCREMENT` is used with an unsupported version type.
- Invalid: `@Version Double version`.
- Valid: `@Version Long version` or another supported integral/decimal type.
- Fix: use `Byte`, `Short`, `Integer`, `Long`, `BigInteger`, or `BigDecimal`.
- First public version: `0.1.0`.

### SKIS011

- Cause: a `@Version` explicitly declares `@Column(nullable = true)`.
- Invalid: `@Version @Column(nullable = true) Long version`.
- Valid: `@Version Long version`.
- Fix: remove `nullable = true`; version columns are implicitly non-null.
- First public version: `0.1.0`.

### SKIS012

- Cause: multiple writable properties map to the same physical column.
- Invalid: two properties both use `@Column(name = "pet_name")` with writes enabled.
- Valid: keep one writable mapping, or make the secondary mapping non-insertable and non-updatable.
- Fix: ensure each writable physical column has one owning property.
- First public version: `0.1.0`.

### SKIS013

- Cause: the entity has no persistent properties.
- Invalid: an entity whose only field is `@Transient String displayLabel`.
- Valid: retain at least one mapped field, getter, or record component.
- Fix: add a persistent property or remove `@SkisEntity`.
- First public version: `0.1.0`.

### SKIS014

- Cause: a writable entity has no `@Id`.
- Invalid: `@SkisEntity public record Pet(String name) {}`.
- Valid: add `@Id Long id`, or use `@SkisEntity(readOnly = true)` for a query-only type.
- Fix: declare one identifier or make the entity read-only.
- First public version: `0.1.0`.

### SKIS015

- Cause: a read-only entity declares `@Version`.
- Invalid: `@SkisEntity(readOnly = true) record Pet(@Version Long version) {}`.
- Valid: remove `@Version`, or make the entity writable and add an identifier.
- Fix: version tracking is only valid for writable entities.
- First public version: `0.1.0`.

### SKIS016

- Cause: property names normalize to the same generated metadata constant.
- Invalid: properties named `url` and `URL`, both of which normalize to `URL`.
- Valid: rename one property, for example `canonicalUrl`.
- Fix: give every property a distinct generated constant name.
- First public version: `0.1.0`.

### SKIS019

- Cause: a required table/column name is blank, or an optional name contains only whitespace.
- Invalid: `@Table(name = "   ")` or `@Column(name = " ")`.
- Valid: use a real identifier, or the empty default when the naming strategy should apply.
- Fix: replace whitespace-only names with valid names or `""`.
- First public version: `0.1.0`.

### SKIS020

- Cause: multiple `@Id` properties are ambiguous before an explicit composite-key declaration exists.
- Invalid: both `tenantId` and `petId` are independently annotated `@Id`.
- Valid: keep one application-assigned `@Id` in the 0.1 line.
- Fix: use one identifier until composite-key declarations are implemented.
- First public version: `0.1.0`.

### SKIS021

- Cause: property names produce the same generated typed-table method.
- Invalid: properties named `as` and `asColumn` both require an `asColumn()` method.
- Valid: rename one property, for example `aliasText`.
- Fix: avoid reserved or normalized table-method collisions.
- First public version: `0.1.0`.

### SKIS022

- Cause: a persistent property type has no built-in JDBC codec.
- Invalid: `URI homepage`, an enum property, or an array other than primitive `byte[]`.
- Valid: map the value to a type in the [JDBC type-mapping matrix](jdbc-type-mappings.md), such as `String` or primitive `byte[]`.
- Fix: use a supported Java type; custom converter/codec registration remains outside the 0.1 scope.
- First public version: `0.1.0`.

### SKIS023

- Cause: a Java primitive is mapped to a nullable column.
- Invalid: `int age` with the default nullable column mapping.
- Valid: `@Column(nullable = false) int age`, or nullable `Integer age`.
- Fix: make the database column non-null or use a wrapper type.
- First public version: `0.1.0`.

### SKIS024

- Cause: one property is both the identifier and optimistic-lock version.
- Invalid: `@Id @Version Long id`.
- Valid: use separate `@Id Long id` and `@Version Long version` properties.
- Fix: separate identity and version state.
- First public version: `0.1.0`.

### SKIS025

- Cause: generated row-decoder code cannot call a record component accessor.
- Invalid: a transformed or malformed record exposes a non-public component accessor.
- Valid: use a normal public record whose canonical accessors are public.
- Fix: make the entity and component accessor accessible to the generated `.skis` package.
- First public version: `0.1.0`.

### SKIS026

- Cause: `@Column.length` is negative.
- Invalid: `@Column(length = -1) String name`.
- Valid: `@Column(length = 100) String name`, or `length = 0` for unspecified.
- Fix: use zero or a positive length.
- First public version: `0.1.0`.

### SKIS027

- Cause: `@Column.precision` is negative.
- Invalid: `@Column(precision = -1) BigDecimal amount`.
- Valid: use zero or a positive precision.
- Fix: correct the precision metadata.
- First public version: `0.1.0`.

### SKIS028

- Cause: `@Column.scale` is negative.
- Invalid: `@Column(scale = -1) BigDecimal amount`.
- Valid: use zero or a positive scale.
- Fix: correct the scale metadata.
- First public version: `0.1.0`.

### SKIS029

- Cause: non-zero precision is smaller than scale.
- Invalid: `@Column(precision = 2, scale = 3) BigDecimal amount`.
- Valid: `@Column(precision = 8, scale = 2) BigDecimal amount`.
- Fix: make `scale <= precision`.
- First public version: `0.1.0`.

### SKIS030

- Cause: a numeric version column is not insertable or not updatable.
- Invalid: `@Version @Column(updatable = false) Long version`.
- Valid: leave both write flags enabled for the version property.
- Fix: numeric version initialization and advancement require insert and update participation.
- First public version: `0.1.0`.

## Simple Bean and Lombok shape

### SKIS031

- Cause: a Simple Entity is neither a record nor a concrete class, or its class is abstract.
- Invalid: `@SkisEntity public interface Pet` or an abstract entity class.
- Valid: use a public record or concrete mutable Bean.
- Fix: Managed Immutable interfaces are deferred; use a supported Simple Entity in 0.1.
- First public version: `0.1.0`.

### SKIS032

- Cause: a Bean entity extends a class other than `Object`.
- Invalid: `@SkisEntity public class Pet extends BaseEntity`.
- Valid: declare all persistent properties directly in `Pet`.
- Fix: remove entity inheritance until its mapping semantics are defined.
- First public version: `0.1.0`.

### SKIS033

- Cause: a Bean entity has no accessible public no-argument constructor.
- Invalid: only `public Pet(Long id)` is available.
- Valid: add `public Pet() {}` or a Lombok-generated public no-args constructor.
- Fix: provide the construction point used by the generated row decoder.
- First public version: `0.1.0`.

### SKIS034

- Cause: a Bean property has no generated-package-accessible read path.
- Invalid: `private Long id` with no public getter.
- Valid: add `public Long getId()`, `public Long id()`, or make the field public.
- Fix: expose a public read accessor with exactly the property type.
- First public version: `0.1.0`.

### SKIS035

- Cause: a Bean property has no generated row-decoder write path.
- Invalid: a private/final property with no public setter.
- Valid: add `public void setId(Long id)`, a fluent writer, or a writable public field.
- Fix: expose one public write path with exactly the property type.
- First public version: `0.1.0`.

### SKIS036

- Cause: field and getter declare different `@Column` values for one property.
- Invalid: the field maps to `pet_name` while the getter maps to `display_name`.
- Valid: put the mapping in one place or make both declarations identical.
- Fix: remove the hidden precedence ambiguity.
- First public version: `0.1.0`.

### SKIS037

- Cause: a getter return type or setter parameter type differs from the property type.
- Invalid: a `Long id` field with `public String getId()`.
- Valid: both accessors use `Long` exactly.
- Fix: align the Java signatures; implicit conversions are not performed.
- First public version: `0.1.0`.

### SKIS038

- Cause: a Lombok-backed type never reaches a supported mutable Simple Entity shape before the
  final annotation-processing round. The diagnostic includes the last structural code.
- Invalid: `@Getter` on private fields without a no-args constructor/writable accessors, or Lombok
  annotations present only on the compile classpath but not enabled as processors.
- Valid: enable Lombok processing and use a shape such as `@Getter @Setter @NoArgsConstructor`.
- Fix: verify processor configuration; `@Value`, builder-only, and immutable all-args-only entities
  remain unsupported in 0.1.
- First public version: `0.1.0`.

### SKIS039

- Cause: the public Bean no-args constructor declares an unsupported checked exception.
- Invalid: `public Pet() throws IOException {}`.
- Valid: remove the checked exception; `SQLException` and unchecked failures may propagate.
- Fix: keep construction compatible with `RowDecoder.decode`.
- First public version: `0.1.0`.

### SKIS040

- Cause: a selected Bean getter or setter declares an unsupported checked exception.
- Invalid: `public Long getId() throws IOException`.
- Valid: remove the checked exception; `SQLException` and unchecked failures may propagate.
- Fix: keep generated Binder/RowDecoder invocation points compatible with their contracts.
- First public version: `0.1.0`.

## Entity rounds and generated output

### SKIS097

- Cause: an entity property or thrown type is still unresolved in the final processing round.
- Invalid: `@SkisEntity public record Pet(@Id Long id, MissingType value) {}` when no processor
  generates `MissingType`.
- Valid: put the type on the compile classpath or generate it in an earlier annotation-processing
  round.
- Fix: correct the processor order and annotation-processor path.
- First public version: `0.1.0`.

### SKIS098

- Cause: `META-INF/skis/entities.idx` cannot be created.
- Invalid build: two aggregators write the same class-output resource, or a stale output blocks it.
- Valid build: one `SkisEntityIndexProcessor` owns a clean class-output index.
- Fix: remove duplicate processor configuration and clean the conflicting output directory.
- First public version: `0.1.0`.

### SKIS099

- Cause: an entity-generated Java source cannot be created.
- Invalid build: a user or another processor already owns `PetMeta`, `PetTable`, `PetBinder`,
  `PetRowDecoder`, or `PetRuntimeModel` in the generated package.
- Valid build: reserve the entity's `.skis` generated names for SKIS.
- Fix: rename/remove the conflicting source and check generated-source output permissions.
- First public version: `0.1.0`.

## Projection declaration and constructor

### SKIS201

- Cause: a projection scan was requested for a type without `@SkisProjection`.
- Invalid: `public record PetSummary(Long id) {}` submitted directly to the projection processor.
- Valid: add `@SkisProjection(entity = Pet.class)`.
- Fix: annotate the result type or do not submit it for projection processing.
- First public version: `0.1.0`.

### SKIS202

- Cause: the projection is neither a class nor a record.
- Invalid: `@SkisProjection(entity = Pet.class) public interface PetSummary`.
- Valid: use a concrete class or record result type.
- Fix: change the projection declaration kind.
- First public version: `0.1.0`.

### SKIS203

- Cause: the projection is nested.
- Invalid: a projection record declared inside `PetViews`.
- Valid: move `PetSummary` to a top-level source file.
- Fix: generated projection providers require a top-level user result type.
- First public version: `0.1.0`.

### SKIS204

- Cause: the projection type is not public.
- Invalid: package-private `record PetSummary(Long id) {}`.
- Valid: `public record PetSummary(Long id) {}`.
- Fix: expose the result type to its generated `.skis` subpackage.
- First public version: `0.1.0`.

### SKIS205

- Cause: the projection type declares type parameters.
- Invalid: `public record PetSummary<T>(T value) {}`.
- Valid: use concrete constructor parameter types.
- Fix: remove projection type parameters.
- First public version: `0.1.0`.

### SKIS206

- Cause: the projection is in the unnamed Java package.
- Invalid: an annotated projection source with no `package` declaration.
- Valid: start with `package example.view;`.
- Fix: move the projection into a named package.
- First public version: `0.1.0`.

### SKIS207

- Cause: the selected projection constructor is not public.
- Invalid: `@ProjectionConstructor private PetSummary(Long id)`.
- Valid: make the selected constructor public.
- Fix: expose the constructor to the generated provider.
- First public version: `0.1.0`.

### SKIS208

- Cause: the selected projection constructor has no parameters.
- Invalid: `public PetSummary() {}`.
- Valid: `public PetSummary(Long id) { ... }`.
- Fix: declare at least one parameter backed by an entity property.
- First public version: `0.1.0`.

### SKIS209

- Cause: the selected projection constructor is variable arity.
- Invalid: `public PetSummary(String... names)`.
- Valid: use a fixed parameter list such as `public PetSummary(String name)`.
- Fix: replace varargs with fixed projection columns.
- First public version: `0.1.0`.

### SKIS210

- Cause: the selected projection constructor declares thrown types.
- Invalid: `public PetSummary(Long id) throws Exception`.
- Valid: use a constructor with no `throws` clause.
- Fix: handle validation outside result construction.
- First public version: `0.1.0`.

### SKIS211

- Cause: more than one constructor is annotated `@ProjectionConstructor`.
- Invalid: mark both the one- and two-argument constructors.
- Valid: mark exactly one public constructor.
- Fix: remove the extra marker.
- First public version: `0.1.0`.

### SKIS212

- Cause: a projection class has zero or multiple public constructors and no explicit selection.
- Invalid: two public constructors without `@ProjectionConstructor`.
- Valid: keep one public constructor or mark one public constructor explicitly.
- Fix: make constructor selection unambiguous.
- First public version: `0.1.0`.

### SKIS213

- Cause: the record canonical constructor cannot be resolved.
- Invalid: a malformed or not-yet-complete record symbol supplied by a collaborating processor.
- Valid: a normal record whose components and canonical constructor types are resolved.
- Fix: ensure generated component types exist before the final processing round.
- First public version: `0.1.0`.

### SKIS214

- Cause: a projection class is abstract.
- Invalid: `public abstract class PetSummary`.
- Valid: use a directly constructible concrete class.
- Fix: remove `abstract` or introduce a concrete result type.
- First public version: `0.1.0`.

### SKIS215

- Cause: a record marks a non-canonical auxiliary constructor with `@ProjectionConstructor`.
- Invalid: mark `PetSummary(Long id) { this(id, ""); }` in a two-component record.
- Valid: use the canonical record constructor without marking an auxiliary constructor.
- Fix: move/remove the marker so the canonical constructor is selected.
- First public version: `0.1.0`.

### SKIS216

- Cause: the selected projection constructor declares its own type parameters.
- Invalid: `public <T> PetSummary(T id)`.
- Valid: `public PetSummary(Long id)`.
- Fix: remove constructor type parameters.
- First public version: `0.1.0`.

## Projection resolution and entity binding

### SKIS217

- Cause: a projection constructor parameter, source entity, or Lombok-backed projection dependency
  remains unresolved in the final processing round.
- Invalid: a constructor uses `GeneratedMoney` but no active processor generates that type.
- Valid: generate or compile every referenced type before processing ends.
- Fix: correct annotation-processor order/path and Lombok processor configuration.
- First public version: `0.1.0`.

### SKIS218

- Cause: a projection parameter type is inaccessible from the generated `.skis` subpackage.
- Invalid: a public projection constructor uses a package-private parameter type or generic argument.
- Valid: use public top-level/nested types and accessible type-use annotations.
- Fix: make the complete parameter type graph accessible.
- First public version: `0.1.0`.

### SKIS219

- Cause: `@SkisProjection.entity` does not name a declared reference type.
- Invalid: `@SkisProjection(entity = int.class)`.
- Valid: `@SkisProjection(entity = Pet.class)` where `Pet` is an entity declaration.
- Fix: select a concrete declared entity type.
- First public version: `0.1.0`.

### SKIS220

- Cause: the projection's source entity fails its own entity-model validation.
- Invalid: a projection targets a writable entity with no `@Id` or an unsupported Bean shape.
- Valid: fix the nested `[SKISxxx]` entity diagnostic first.
- Fix: compile the source entity successfully before compiling its projection.
- First public version: `0.1.0`.

### SKIS221

- Cause: a projection constructor parameter selects no persistent entity property.
- Invalid: `record PetSummary(String label)` when the entity property is named `name`.
- Valid: rename the parameter to `name` or add `@ProjectionProperty("name")`.
- Fix: bind every parameter to an existing persistent property.
- First public version: `0.1.0`.

### SKIS222

- Cause: a projection parameter type does not match its entity property type after boxing.
- Invalid: `String id` for an entity `Long id` property.
- Valid: use `Long id`.
- Fix: make the Java types exactly compatible; SKIS performs no implicit conversion.
- First public version: `0.1.0`.

### SKIS223

- Cause: a primitive projection parameter selects a nullable entity property.
- Invalid: primitive `long ownerId` selecting nullable `Long ownerId`.
- Valid: use `Long ownerId`, or make the entity column non-null.
- Fix: preserve SQL `NULL` in the result type.
- First public version: `0.1.0`.

### SKIS224

- Cause: `@ProjectionProperty` contains a blank property name.
- Invalid: `@ProjectionProperty("  ") String name`.
- Valid: `@ProjectionProperty("name") String label`.
- Fix: name a real persistent entity property.
- First public version: `0.1.0`.

## Projection generated output

### SKIS298

- Cause: `META-INF/skis/projections.idx` cannot be created.
- Invalid build: duplicate index aggregators or a stale conflicting class-output resource.
- Valid build: one `SkisProjectionIndexProcessor` owns the projection index.
- Fix: remove duplicate processor configuration and clean the conflicting output.
- First public version: `0.1.0`.

### SKIS299

- Cause: a generated projection Provider source cannot be created.
- Invalid build: a user source already owns `<ResultType>Projection` in the `.skis` package.
- Valid build: reserve generated projection provider names for SKIS.
- Fix: rename/remove the conflicting source and verify generated-source output permissions.
- First public version: `0.1.0`.

## Runtime index failures

Generated indexes are validated when `SkisExecutorFactory` assembles the runtime. Runtime failures
use `SkisConfigurationException` rather than a compile-time `SKISxxx` code because the conflict can
span already-built modules. Messages include the index URL and, when a line exists, its number, and
reject:

- a missing, invalid, duplicate, or incompatible generated-model ABI header;
- duplicate Provider entries within one index or across multiple dependency indexes;
- different entity Providers supplying the same canonical metadata or Java entity type;
- different projection Providers supplying the same user result type;
- missing, incorrectly typed, non-constructible, null-returning, or link-incompatible Providers.

Do not merge or shade generated indexes by silently discarding entries. Re-run APT for the owning
module and keep exactly one Provider for each entity Java type and projection result type.
