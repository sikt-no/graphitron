# Plan: Rewrite-owned Maven plugin

> **Status:** Done (initial landing `17504dd`; review-round-2 follow-ups `6026b98` + `388065b` cut dead `<scalars>` / `<maxAllowedPageSize>` knobs, normalise `<outputDirectory>`, make `validate` usable standalone from the CLI, unify error handling across Mojos, widen `SchemaInputExpander` catch, extract `logWarnings` helper, trim stale javadoc)
>
> Sub-item of the "Dissolve `graphitron-schema-transform` module"
> umbrella. Lands last in the umbrella's rewrite-plumbing arc, after
> [plan-rewrite-owns-schema-loading.md](plan-rewrite-owns-schema-loading.md)
> and [plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md).
> By the time this plan runs: `RewriteSchemaLoader`, `SchemaInput`,
> `SchemaInputResolver`, the tag/note appliers, and a minimal
> `RewriteContext` record all exist in rewrite core; the legacy
> plugin has already lost its `enableRewrite` entry point, so
> rewrite has no Mojo surface to relocate, only to introduce.
> This plan is pure introduction: new artifact, clean config
> schema, full `RewriteContext`, all the organic-growth warts of
> the retiring legacy plugin left behind.
>
> Drives simplification for the remaining umbrella work: the
> `<outputSchemas>` plan and the final retirement of
> `graphitron-schema-transform` land on this plugin, not on the
> legacy one.

## Goal

Ship `graphitron-rewrite-maven` as a new Maven plugin artifact with
two goals (`generate`, `validate`) and a minimal, opinionated config
surface. The plugin replaces static-config singletons with a
per-invocation context, drops every legacy escape hatch, and exposes
exactly the knobs rewrite consumers need today.

Driving principle: simplification. Every config element inherited from
`graphitron-maven-plugin` must justify its continued existence against
the concrete wart it papers over. Unjustified knobs get cut, not
migrated.

## Scope

**In scope**

- New artifact `graphitron-rewrite-maven` at
  `graphitron-rewrite/graphitron-rewrite-maven/`.
- Two Mojos (`generate`, `validate`) driving the rewrite pipeline
  via the `RewriteContext` record (no static singletons).
- `<schemaInputs>` XML surface and its `SchemaInputBinding` POJO,
  introduced in this plan (tagged-inputs left the config layer to
  this plan on purpose).
- Glob expansion: turn each `<schemaInput>`'s `<pattern>` into one
  or more concrete `SchemaInput` records via Maven's
  `DirectoryScanner`, rooted at `project.getBasedir()`. The
  empty-match fail-fast lives here (plugin-level user-config
  diagnostic). See §Glob expansion.
- Plugin packaging, lifecycle bindings, `plugin.xml` descriptor,
  integration test harness.
- Expansion of the minimal `RewriteContext` (introduced by
  tagged-inputs with `schemaInputs` + `basedir`) with the remaining
  plugin knobs: output paths, packages, named references, scalars,
  page-size cap.
- Deletion of `RewriteConfig` statics entirely. Rewiring 68 call
  sites across 18 files in rewrite core to read from `RewriteContext`
  instead. Threading approach described in §Removing `RewriteConfig`.
  Matches the plan's "no static singletons" driving principle;
  without this step, `RewriteConfig` would sit as an orphaned
  never-populated static bag post-landing.
- Legacy-plugin cleanup: delete the `enableRewrite` /
  `disableLegacy` / `failOnRewriteValidationError` plumbing that
  tagged-inputs intentionally left in place. Four files across
  `graphitron-maven-plugin` and `graphitron-codegen-parent`; see
  §Legacy-plugin cleanup.
- `graphitron-rewrite-test/pom.xml` migration: switch its
  `<plugin>graphitron-maven-plugin</plugin>` execution to
  `<plugin>graphitron-rewrite-maven</plugin>`, dropping the
  `<enableRewrite>` / `<disableLegacy>` configuration elements in
  the same commit as the Mojo cleanup (atomic: trunk never
  transits through a state where rewrite-test has no generation
  entry point).
- Migration documentation: what consumer POMs look like before vs.
  after.

**Out of scope**

- The resolver + appliers themselves (landed by
  [plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md);
  this plan just constructs the `RewriteContext` they consume).
- Implementing `<outputSchemas>` (future umbrella sub-item; plugin
  grows an element when that plan ships).
- Decommissioning `graphitron-maven-plugin` (the umbrella's "Retire
  `graphitron-schema-transform`" landing marker; legacy plugin keeps
  running until then for its legacy-only path).
- Any classifier / emitter / validator refactor inside
  `graphitron-rewrite` proper. This plan is strictly the Maven-plugin
  boundary and the config object that flows through it.

## Current state: what we're leaving behind

Audit of `graphitron-maven-plugin` surfaced seven concrete warts that
the new plugin does not inherit. Each is tied to a file + line in the
legacy code so the cut is unambiguous. Wart 4 (`enableRewrite` /
`disableLegacy` gating + `failOnRewriteValidationError`) is deleted by
this plan; see §Legacy-plugin cleanup for the file list.

1. **Static config singletons.** `GeneratorConfig` (`graphitron-codegen-parent/.../GeneratorConfig.java`)
   and `RewriteConfig` (`graphitron-rewrite/.../RewriteConfig.java`) are
   both static field bags. `GenerateMojo.java:185-198` threads a subset
   of fields from one static bag into the other. Thread-unsafe;
   two-stage population; subset leak between stages.

2. **Three load paths for the same config.** `GeneratorConfig.loadProperties`,
   `loadValidatorProperties`, `loadIntrospectorProperties` (at lines
   93-128, 134-164, 170-189). Validator stub even sets `outputPackage`
   to `"validation.unused"` to suppress downstream NPEs. Every new
   field is added in three places.

3. **Schema-file intent collapsed into two overlapping parameters.**
   `AbstractGraphitronMojo.schemaFiles` (default: transform output)
   vs. `GenerateMojo.userSchemaFiles` (user-provided). Neither
   migrates; `<schemaInputs>` takes their place.

4. **Rewrite / legacy gating toggles.** `enableRewrite` / `disableLegacy`
   at `GenerateMojo.java:114,123`; `ValidateMojo.failOnRewriteValidationError`.
   Deleted by this plan (see §Legacy-plugin cleanup). The new plugin is
   rewrite-only from day one; no gates.

5. **Embedded transform logic.** `GenerateMojo` optionally invokes
   `SchemaTransformRunner` inline (`GenerateMojo.java:138-141`) while
   `TransformMojo` also exists as a standalone goal. Two ways to run
   the same transform; consumers get it wrong.

6. **`@Execute` annotation trickery.** `ValidateMojo` declares phase
   `VALIDATE` but forces `GENERATE_RESOURCES` execution via `@Execute`
   (line 32); `IntrospectMojo` has no `@Mojo` phase but `@Execute`'s
   `GENERATE_RESOURCES`. Execution order hidden from the declaration.

7. **Phantom static fields.** `GeneratorConfig.alwaysUsePrimaryKeyInSplitQueries`
   (hardcoded to `true`, never exposed as `@Parameter`),
   `GeneratorConfig.nodeExists` (set by the schema builder, read only
   via getter; it's schema-derived state, not config).

None of the above migrate. Each is dropped, inlined, or replaced by a
single narrowly-scoped construct in the new plugin.

## Design

### Module layout

```
graphitron-rewrite/
├── graphitron-rewrite/                      # pipeline code; this plan expands RewriteContext here
├── graphitron-rewrite-fixtures/             # unchanged
├── graphitron-rewrite-test/                 # unchanged
└── graphitron-rewrite-maven/                # NEW
    ├── pom.xml                              # packaging=maven-plugin
    └── src/main/java/no/sikt/graphitron/rewrite/maven/
        ├── AbstractRewriteMojo.java         # shared @Parameter surface
        ├── GenerateMojo.java                # primary goal
        ├── ValidateMojo.java                # validate-only goal
        ├── SchemaInputBinding.java          # POM XML binding for <schemaInput>
        ├── SchemaInputExpander.java         # <pattern> → List<SchemaInput> via DirectoryScanner
        ├── NamedReferenceBinding.java       # POM XML binding for <namedReference>
        └── ScalarBinding.java               # POM XML binding for <scalar>
```

`RewriteContext` stays in `no.sikt.graphitron.rewrite` (rewrite core) where
tagged-inputs put it; this plan edits that file in place to add the new
fields. `ScalarMapping` (the rewrite-core record `List<ScalarMapping>` on
the context points at) also lives in rewrite core, next to `SchemaInput`.

Artifact: `graphitron-rewrite-maven` (non-standard Maven-plugin
suffix; see §Goal-prefix note at the end of this section). Package:
`no.sikt.graphitron.rewrite.maven`.

### `GenerateMojo` shape

```java
public abstract class AbstractRewriteMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter List<SchemaInputBinding> schemaInputs;
    @Parameter(defaultValue = "${project.build.directory}/generated-sources")
    String outputDirectory;
    @Parameter(required = true) String outputPackage;
    @Parameter(required = true) String jooqPackage;
    @Parameter List<NamedReferenceBinding> namedReferences;
    @Parameter List<ScalarBinding> scalars;
    @Parameter(defaultValue = "1000") int maxAllowedPageSize;

    protected RewriteContext buildContext() { /* see §RewriteContext */ }
}

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES,
      requiresDependencyResolution = ResolutionScope.COMPILE,
      threadSafe = true)
public class GenerateMojo extends AbstractRewriteMojo {
    @Override
    public void execute() {
        new GraphQLRewriteGenerator(buildContext()).generate();
    }
}
```

**That is the entire `@Parameter` list for `generate`.** Eight
parameters vs. the legacy plugin's 18+ across its Mojos. `ValidateMojo`
inherits the same eight from `AbstractRewriteMojo`; it omits nothing
because schema loading + `<schemaInputs>` resolution need the full
input set. Everything else from the legacy audit is dropped (see
§Current state).

Fields are package-private so `RewriteContext.from` can read them
without getters; Maven's reflective `@Parameter` injection works
either way, and package-private keeps the Mojo→context bridge as two
lines of field access rather than eight getter methods. `threadSafe =
true` is the immediate win from eliminating `RewriteConfig` statics
(see §Scope's `RewriteConfig` removal bullet).

### `RewriteContext`: expanded to carry full plugin config

Tagged-inputs already put `RewriteContext` in
`no.sikt.graphitron.rewrite` with two fields (`schemaInputs`,
`basedir`). This plan expands the record with the remaining knobs;
the generator signature stays `new GraphQLRewriteGenerator(ctx)`
across both landings.

Rewrite-core record (edited in place; canonical constructor only):

```java
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Path basedir,
    Path outputDirectory,
    String outputPackage,
    String jooqPackage,
    Map<String, String> namedReferences,
    List<ScalarMapping> scalars,
    int maxAllowedPageSize
) {}
```

Plugin-module factory (lives on `AbstractRewriteMojo`, not on the
record, so rewrite core has no compile-time dependency on the plugin
module):

```java
// inside AbstractRewriteMojo
RewriteContext buildContext() {
    var basedir = project.getBasedir().toPath();
    return new RewriteContext(
        SchemaInputExpander.expand(schemaInputs, basedir),
        basedir,
        Path.of(outputDirectory),
        outputPackage,
        jooqPackage,
        toNamedReferenceMap(namedReferences),
        toScalarMappings(scalars),
        maxAllowedPageSize
    );
}
```

`SchemaInputExpander` lives in the plugin module (see §Glob
expansion). `toNamedReferenceMap` and `toScalarMappings` are
private statics on `AbstractRewriteMojo`. `RewriteConfig` deletes
once all rewrite-core
readers migrate; no new statics are introduced.

### Goals (the final list)

Two goals:

- **`generate`** (default phase `GENERATE_SOURCES`): runs the full
  pipeline and writes generated Java.
- **`validate`** (default phase `VALIDATE`): runs schema loading,
  `<schemaInputs>` resolution, and `GraphitronSchemaValidator`
  without writing any output. Separate Mojo (not a
  `<skipCodeOutput>true</skipCodeOutput>` flag) so the declarative
  phase binding lands at `VALIDATE`, matching consumer expectations
  for `mvn validate`. Both Mojos share the same `RewriteContext`
  construction path; `ValidateMojo` simply stops before the emitter
  step.

Dropped from the legacy plugin:

- `transform`: the only transformation the rewrite plugin does is
  `<schemaInputs>` tagging, which runs inside `generate` / `validate`.
- `watch`: IDE incremental builds cover the dev-loop case. Not
  revisiting without concrete consumer ask.

`introspect` is ported in a separate umbrella sub-item (see roadmap);
consumers who need LSP config keep using `graphitron-maven-plugin:introspect`
until that plan ships. Not blocked by this plan.

### Goal-prefix note

Maven's default goal-prefix inference requires an artifactId of the
form `*-maven-plugin` or `maven-*-plugin`. `graphitron-rewrite-maven`
doesn't match, so `maven-plugin-plugin` needs an explicit
`<goalPrefix>graphitron-rewrite</goalPrefix>` in the plugin pom.
Consumers invoke goals as `mvn graphitron-rewrite:generate` /
`mvn graphitron-rewrite:validate`.

### Config bindings (POM → Java)

Three small POJOs that Maven populates from XML, all new in this
plan (rewrite core has `SchemaInput` but no XML binding yet):

- `SchemaInputBinding`: fields `pattern`, `tag`, `descriptionNote`.
  Expanded into one-or-more rewrite-core `SchemaInput` records by
  `SchemaInputExpander` (see §Glob expansion).
- `NamedReferenceBinding`: fields `name`, `className`. Collapses into
  a `Map<String, String>` on the context. Renamed from legacy's
  `<externalReferences>` / `ExternalMojoClassReference(name, fullyQualifiedClassName)`
  to match rewrite's own internal terminology (`BuildContext` /
  `RewriteConfig.namedReferences`). `fullyQualifiedClassName` shortens
  to `className`; one-time consumer XML edit.
- `ScalarBinding`: fields `scalarName`, `className`. Dedicated type
  rather than reusing the named-reference POJO; scalar names are
  GraphQL schema identifiers and deserve a distinct field name.

Deliberate: one POJO per concept. No shared base class.

`ScalarMapping` (the rewrite-core record the context's `List<ScalarMapping>`
points at) lives in rewrite core at
`no.sikt.graphitron.rewrite.schema.ScalarMapping`, next to
`SchemaInput`. `ScalarBinding.toScalarMapping()` is the plugin-module
conversion; rewrite core has no compile-time dep on the binding.

### Glob expansion

Rewrite-core's `SchemaInput` holds one concrete source per entry;
the `<pattern>` → file-list expansion lives here in the plugin so
rewrite-core stays filesystem-agnostic. `SchemaInputExpander` uses
Maven's `DirectoryScanner` (standard idiom for Maven plugins
processing file patterns, already on the plugin classpath via
`maven-plugin-api`):

```java
// no.sikt.graphitron.rewrite.maven.SchemaInputExpander
static List<SchemaInput> expand(List<SchemaInputBinding> bindings, Path basedir) {
    var expanded = new ArrayList<SchemaInput>();
    for (int i = 0; i < bindings.size(); i++) {
        var b = bindings.get(i);
        var scanner = new DirectoryScanner();
        scanner.setBasedir(basedir.toFile());
        scanner.setIncludes(new String[]{b.pattern});
        scanner.scan();
        var matches = scanner.getIncludedFiles();
        if (matches.length == 0) {
            throw new MojoExecutionException(
                "<schemaInput pattern='" + b.pattern + "'> matched no files (entry #" + i + ")");
        }
        for (var rel : matches) {
            var abs = basedir.resolve(rel).toAbsolutePath().normalize().toString();
            expanded.add(new SchemaInput(
                abs,
                Optional.ofNullable(b.tag).filter(s -> !s.isEmpty()),
                Optional.ofNullable(b.descriptionNote).filter(s -> !s.isEmpty())
            ));
        }
    }
    return expanded;
}
```

~40 LOC. Ant-style patterns (`**`, `*`, `?`) are what
`DirectoryScanner` natively supports; consumers write patterns
relative to the project basedir. Empty-string `<tag>` or
`<descriptionNote>` normalise to `Optional.empty()` so the common
"empty XML element" case doesn't end up applying
`@tag(name: "")`.

Cross-boundary invariants (overlap on `sourceName`) stay in
rewrite-core's `SchemaInputAttribution.build(...)`; the plugin just
hands over a `List<SchemaInput>`. If two bindings match the same
file, rewrite-core throws at attribution time with both entries in
the message.

Expander tests (`SchemaInputExpanderTest`, in the plugin module):

- Single pattern, one match: returns one `SchemaInput` with
  absolute-normalised path.
- Single pattern, `**` glob, three matches: returns three
  `SchemaInput`s in alphabetic / scanner-order; all share the
  binding's tag and note.
- Zero-match pattern: throws `MojoExecutionException` with the
  pattern and binding index in the message.
- Empty-string tag: normalises to `Optional.empty()`.
- Empty-string descriptionNote: normalises to `Optional.empty()`.
- Malformed pattern (bracket-unclosed etc.): throws with the
  pattern and underlying scanner error.

### Dropped legacy config elements

Beyond the Mojo-level cuts in §Current state, three
extension-point knobs also drop because rewrite does not consume
them:

- `<externalReferenceImports>`: flat `Set<String>` on
  `AbstractGraphitronMojo`. Referenced only in legacy
  `GeneratorConfig`; no reader in `graphitron-rewrite/`. JavaPoet's
  import computation from `ClassName` objects covers what this
  Set was manually tracking.
- `<globalRecordTransforms>`: legacy-only; rewrite has no reader.
- `<recordValidation>` / `<codeGenerationThresholds>` /
  `<optionalSelect>` / `<useJdbcBatchingForDeletes>` /
  `<useJdbcBatchingForInserts>` / `<validateOverlappingInputFields>` /
  `<failOnMerge>`: also legacy-only; rewrite has no reader. Listed
  together because they share a migration note: consumers who need
  the same behaviour under rewrite open a roadmap item for that
  specific capability.

### Removing `RewriteConfig`

`RewriteConfig` is rewrite core's static bag. Tagged-inputs already
deleted the Mojo call site that populated it; this plan deletes the
class and rewires every reader onto `RewriteContext`.

**Call-site inventory** (68 reads across 18 files in rewrite core,
post-tagged-inputs state):

| File                                        | Reads |
|---------------------------------------------|-------|
| `generators/SplitRowsMethodEmitter.java`    |  18   |
| `generators/TypeFetcherGenerator.java`      |  10   |
| `generators/FetcherEmitter.java`            |   8   |
| `generators/GeneratorUtils.java`            |   6   |
| `generators/InlineLookupTableFieldEmitter.java` | 5 |
| `generators/InlineTableFieldEmitter.java`   |   4   |
| `GraphQLRewriteGenerator.java`              |   3   |
| `generators/WiringClassGenerator.java`      |   2   |
| `FieldBuilder.java`                         |   2   |
| `BuildContext.java`                         |   2   |
| `generators/util/ConnectionResultClassGenerator.java` | 1 |
| `generators/util/ConnectionHelperClassGenerator.java` | 1 |
| `generators/schema/ObjectTypeGenerator.java` |  1   |
| `generators/schema/GraphitronSchemaClassGenerator.java` | 1 |
| `generators/schema/GraphitronFacadeGenerator.java` | 1 |
| `generators/TypeClassGenerator.java`        |   1   |
| `generators/GraphitronWiringClassGenerator.java` | 1 |
| `GraphitronSchemaBuilder.java`              |   1   |

Fields read: `outputPackage` (~40), `getGeneratedJooqPackage` (~20),
`namedReferences` (3), `outputDirectory` (1), `generatorSchemaFiles`
(1). The two dominant reads are used to construct `ClassName`
objects for emitted rewrite / jOOQ types; a pair of helpers on
`BuildContext` (e.g. `bctx.rewriteClass(subPkg, name)` /
`bctx.jooqTablesClass()`) can collapse dozens of reads into a few
method calls post-migration, but that's a follow-up cleanup rather
than a requirement of this plan.

**`run()` → `generate()` rename.** The instance method is currently
called `run()` rather than `generate()` because Java disallows an
instance and a static method with the same name in one class, and the
static `generate()` is still live. Deleting the static `generate()` as
part of this plan unblocks renaming `run()` to `generate()`; the rename
must happen in the same commit as the static's deletion so no caller is
left with a dangling `run()` call. The Mojo snippet above already shows
`new GraphQLRewriteGenerator(buildContext()).generate()`.

**Threading approach: constructor parameter.** `GraphQLRewriteGenerator`
is already instance-based post-tagged-inputs and holds `RewriteContext
ctx`. Propagate `ctx` to each generator that reads from `RewriteConfig`
today:

1. `GraphQLRewriteGenerator.generate()` passes `ctx` into
   `GraphitronSchemaBuilder.build(registry, ctx)` (new parameter).
2. `GraphitronSchemaBuilder.build` constructs `BuildContext` as
   `new BuildContext(schema, catalog, ctx)`; `BuildContext` gains a
   `RewriteContext ctx()` accessor and its two `namedReferences`
   reads become `ctx.namedReferences()`.
3. `FieldBuilder` already threads through `BuildContext`; its two
   reads migrate to `bctx.ctx().outputPackage()` /
   `bctx.ctx().namedReferences()`. No new parameter.
4. Emitters invoked from `GraphQLRewriteGenerator` (type/fetcher
   class generators) take `ctx` as a constructor argument or a
   method argument on their `generate(...)` entry point. Each
   generator picks the pattern that matches its current surface;
   static factories become instance methods where necessary.
5. `GeneratorUtils` is a static-helper class; its reads migrate to
   methods that accept `BuildContext` (its callers already thread
   one). Static-method signatures gain the `BuildContext` parameter
   or the utility becomes instance-based, whichever the call-site
   density favours.

Generators invoked purely as static helpers (e.g. inline emitters)
take `ctx` as a method parameter rather than holding state.
Principle: `RewriteContext` is passed, never held in a static or
`ThreadLocal`.

**Deliverable:** 68 call sites edited across 18 files; ~150–200 LOC
net change in rewrite core (mostly ctor/method parameters threading
a single object, not logic changes). Plus the deletion of
`RewriteConfig.java` (66 LOC).

**Test fixtures.** Any rewrite-core test that currently populates
`RewriteConfig.setProperties(...)` in `@BeforeEach` switches to
constructing a `RewriteContext` directly. Tagged-inputs already
established this pattern for `schemaInputs` + `basedir`; extending to
the new fields is mechanical. Remove `RewriteConfig.clear()` calls
from `@AfterEach` hooks when their only purpose was clearing the
statics.

### Legacy-plugin cleanup

Tagged-inputs left the Mojo's `enableRewrite=true` branch in place
so `graphitron-rewrite-test` could keep building via the legacy
plugin. This plan deletes that branch along with its supporting
plumbing. `disableLegacy` goes too: without `enableRewrite`, setting
`disableLegacy=true` becomes "generate nothing," a degenerate state
nobody should reach for.

Files to edit:

- `graphitron-maven-plugin/.../mojo/GenerateMojo.java`: delete the
  `@Parameter enableRewrite` + `@Parameter disableLegacy` declarations,
  both getter overrides, the `if (!disableLegacy)` wrap, and the
  `if (enableRewrite) { RewriteConfig.setProperties(...);
  new GraphQLRewriteGenerator(ctx).generate(); }` block (the
  `GraphQLRewriteGenerator(ctx)` variant is what tagged-inputs
  produced). Legacy call unwraps to unconditional
  `GraphQLGenerator.generate()`.
- `graphitron-maven-plugin/.../mojo/ValidateMojo.java`: delete the
  `@Parameter failOnRewriteValidationError`, its consumer, and the
  rewrite-error downgrade path + associated help text.
- `graphitron-codegen-parent/.../generate/Generator.java`: delete
  the `default boolean enableRewrite()` + `default boolean
  disableLegacy()` interface methods.
- `graphitron-codegen-parent/.../configuration/GeneratorConfig.java`:
  delete `private static boolean enableRewrite`, the
  `enableRewrite = mojo.enableRewrite()` line in `loadProperties`,
  the getter, and the setter.

And in the same commit, migrate `graphitron-rewrite-test/pom.xml`:

- `<plugin>graphitron-maven-plugin</plugin>` → `<plugin>graphitron-rewrite-maven</plugin>`.
- Drop `<enableRewrite>true</enableRewrite>` and
  `<disableLegacy>true</disableLegacy>` (parameters no longer exist).
- `<schemaFiles>` → `<schemaInputs><schemaInput><pattern>...</pattern></schemaInput></schemaInputs>`
  (single-entry, no tag or note; rewrite-test doesn't exercise
  tag/note behaviour from this path).

Atomic: trunk moves from "rewrite-test generates via the legacy
Mojo's enableRewrite branch" straight to "rewrite-test generates
via the new plugin." No intermediate state where rewrite-test has
no generation entry point.

### Lifecycle and packaging

- `graphitron-rewrite/pom.xml`: add `<module>graphitron-rewrite-maven</module>`
  to the existing three-entry `<modules>` list. Without this, `mvn
  install` at the repo root won't build the new plugin.
- `graphitron-rewrite-maven/pom.xml`: `<packaging>maven-plugin</packaging>`;
  depends on `graphitron-rewrite` (source access to generator +
  `RewriteContext`) and the Maven plugin API. No dep on
  `graphitron-common` (the schema-loading plan already severed
  rewrite's build-time common dep).
- `<prerequisites><maven>3.9</maven></prerequisites>`.
- `maven-plugin-plugin` with two executions (mirrors
  `graphitron-maven-plugin/pom.xml`): `descriptor` (generates
  `plugin.xml` from the `@Mojo` / `@Parameter` annotations) and
  `help-mojo` (generates the `help` goal). Set
  `<goalPrefix>graphitron-rewrite</goalPrefix>` in the plugin
  configuration.
- Default lifecycle binding: `GENERATE_SOURCES`, declarative via
  `@Mojo`. No `@Execute`.
- Java: plugin code targets Java 21 (build-time); plugin output
  classes targeted at 17 (generator's existing output contract).

## Migration

One-time consumer migration. Every rewrite-mode consumer edits their
pom.xml when switching to the new plugin; we simplify aggressively and
don't preserve legacy config names.

Full rename table:

| Legacy element                    | New element          | Notes |
|-----------------------------------|----------------------|-------|
| `<enableRewrite>` / `<disableLegacy>` | (removed)        | plugin is rewrite-only |
| `<schemaFiles>` / `<userSchemaFiles>` | `<schemaInputs>` | resolver/appliers landed via tagged-inputs; XML binding introduced here |
| `<outputPath>`                    | `<outputDirectory>`  | standard Maven naming |
| `<outputPackage>`                 | `<outputPackage>`    | unchanged, now required |
| `<jooqGeneratedPackage>`          | `<jooqPackage>`      | shorter |
| `<externalReferences>` (`<externalReference><name/><fullyQualifiedClassName/>`) | `<namedReferences>` (`<namedReference><name/><className/>`) | matches rewrite's own `namedReferences` terminology; field shortens |
| `<externalReferenceImports>`      | (removed)            | not consumed by rewrite |
| `<scalars>` (reuses ExternalMojoClassReference) | `<scalars>` (`<scalar><scalarName/><className/>`) | dedicated POJO; `<name>` → `<scalarName>`, `<fullyQualifiedClassName>` → `<className>` |
| `<transform>` (nested block)      | (removed)            | tagging now via `<schemaInputs>`. **Gotcha:** existing rewrite-mode consumers using `<transform>` will hit a duplicate-directive parse error once they migrate (the legacy transform writes `generator-schema.graphql` with directive declarations embedded; rewrite auto-injects directives, causing a collision). Drop `<transform>` and point `<schemaInputs>` at the raw schema files. This is the same fix the schema-loading landing applied to `graphitron-rewrite-test/pom.xml`. |
| `<maxAllowedPageSize>`            | `<maxAllowedPageSize>` | unchanged |
| `<globalRecordTransforms>` / `<recordValidation>` / `<codeGenerationThresholds>` / `<optionalSelect>` / `<useJdbcBatchingForDeletes>` / `<useJdbcBatchingForInserts>` / `<validateOverlappingInputFields>` / `<failOnMerge>` / `<makeNodeStrategy>` / `<experimental_requireTypeIdOnNode>` | (removed) | legacy-only; no rewrite reader |

Before:
```xml
<plugin>
  <groupId>no.sikt.graphitron</groupId>
  <artifactId>graphitron-maven-plugin</artifactId>
  <configuration>
    <enableRewrite>true</enableRewrite>
    <disableLegacy>true</disableLegacy>
    <schemaFiles>...</schemaFiles>
    <outputPath>...</outputPath>
    <outputPackage>...</outputPackage>
    <jooqGeneratedPackage>...</jooqGeneratedPackage>
    <externalReferences>
      <externalReference>
        <name>AccessControl</name>
        <fullyQualifiedClassName>no.sikt.AccessControl</fullyQualifiedClassName>
      </externalReference>
    </externalReferences>
  </configuration>
</plugin>
```

After:
```xml
<plugin>
  <groupId>no.sikt.graphitron</groupId>
  <artifactId>graphitron-rewrite-maven</artifactId>
  <configuration>
    <schemaInputs>...</schemaInputs>
    <outputDirectory>...</outputDirectory>
    <outputPackage>no.sikt.example.graphql</outputPackage>
    <jooqPackage>no.sikt.example.jooq</jooqPackage>
    <namedReferences>
      <namedReference>
        <name>AccessControl</name>
        <className>no.sikt.AccessControl</className>
      </namedReference>
    </namedReferences>
  </configuration>
</plugin>
```

No migration for consumers still on legacy-only
(`enableRewrite=false`). They keep using `graphitron-maven-plugin`
until the legacy retirement landing marker fires.

## Tests

### Unit: `GenerateMojoTest`

- Mojo reads all 8 parameters and produces a matching `RewriteContext`.
- Missing required parameter (`outputPackage` / `jooqPackage`) fails
  the build with the standard Maven "parameter not set" message.
- `schemaInputs` parses correctly into `List<SchemaInputBinding>` and
  round-trips into `List<SchemaInput>`.

### Unit: `RewriteContextTest`

- Immutability: record is unmodifiable.
- `from(mojo, project)` normalises `outputDirectory` to an absolute
  `Path` rooted at `project.basedir`.

### Integration: `it/basic-generate`

Uses the Maven Invoker Plugin (standard for Maven-plugin ITs). Layout:

```
graphitron-rewrite-maven/
├── pom.xml                                  # <maven-invoker-plugin> wired here
└── src/it/
    ├── settings.xml                         # uses ${project.version} via filtering
    ├── basic-generate/
    │   ├── invoker.properties               # goals=graphitron-rewrite:generate
    │   ├── pom.xml                          # consumer pom under test
    │   ├── src/main/resources/schema.graphqls
    │   └── verify.groovy                    # asserts generated files exist + compile
    └── missing-schema-inputs/
        ├── invoker.properties               # expectedFailure=true
        ├── pom.xml                          # <schemaInputs> absent
        └── verify.groovy                    # asserts the error message text
```

Groovy verify scripts (the Invoker plugin default; BeanShell is
deprecated in current Invoker versions). Two ITs total:

- `basic-generate`: happy path; asserts the expected generated files
  appear under `target/generated-sources/...`.
- `missing-schema-inputs`: omits `<schemaInputs>`; asserts the build
  fails with a precise message (not an NPE), matched via
  `verify.groovy` on `build.log`.

**jOOQ dependency.** The IT's consumer pom declares a runtime dep on
`graphitron-rewrite-fixtures` (the existing rewrite fixture module
already carries pre-generated jOOQ classes for a minimal test
schema). The IT's `<jooqPackage>` parameter points at that fixtures
package. No per-IT jOOQ regeneration step.

**Plugin version resolution.** `src/it/settings.xml` and the IT
`pom.xml` both reference `${project.version}` via the Invoker
plugin's property filtering, so ITs track the in-progress plugin
version without hand-editing.

### No parity matrix with legacy

This plan explicitly doesn't test "new plugin matches legacy plugin's
output byte-for-byte". The generator code underneath is the same
`GraphQLRewriteGenerator`; Mojo wrapping is the only difference. The
generator's own tests (`graphitron-rewrite-test`) cover output
correctness.

## Open decisions

**D1. Artifact name.** Resolved: `graphitron-rewrite-maven`. Requires
explicit `<goalPrefix>graphitron-rewrite</goalPrefix>` in the plugin
pom (see §Goal-prefix note); consumers invoke
`mvn graphitron-rewrite:generate` / `mvn graphitron-rewrite:validate`.

**D2. Extension-point config shape.** Resolved: keep as nested
`@Parameter` XML elements; simplify aggressively (no backwards
compatibility with legacy XML). Renamed to `<namedReferences>` (from
`<externalReferences>`) to match rewrite's internal vocabulary; inner
POJO field shortens to `className` (from `fullyQualifiedClassName`);
scalars get their own POJO (`<scalar><scalarName/><className/>`)
rather than reusing the namedReference type. `<externalReferenceImports>`
drops entirely; no rewrite reader. Consumer migration is one-time
and covered by the §Migration table.

**D3. `validate` goal.** Resolved: included as a separate Mojo at
phase `VALIDATE` (see §Design: Goals). Shares the `RewriteContext`
construction path with `generate`; stops before the emitter.

**D4. `watch` goal.** Resolved: dropped. IDE incremental builds
cover the dev-loop case; no Maven-goal file watcher ships. Not
revisiting without concrete consumer ask.

**D5. `@Parameter` defaults for `outputPackage` / `jooqPackage`.**
Resolved: require explicit. Both are `required = true`; omitting
either fails the build with Maven's standard "parameter not set"
error. No Sikt-flavoured default, no `${project.groupId}`-derived
default; consumers know their own package layout.

**D6. Java release target.** Resolved: plugin code is Java 21; the
generator's existing `release=17` ratchet pins output. No
plugin-level knob.

## Roadmap integration

Roadmap sub-item sequence under the "Dissolve `graphitron-schema-transform`
module" umbrella:

1. Rewrite owns schema loading + directive auto-injection
2. Rewrite owns pattern-matched `@tag` + description notes
3. **Rewrite-owned Maven plugin (this plan)**
4. Port `introspect` goal to `graphitron-rewrite-maven`

The `<schemaInputs>` plan's D1 (replace vs. coexist with
`<schemaFiles>`) is resolved by this plan: the new plugin has no
`<schemaFiles>`, so the coexist option never materialises.

On landing, move this plan's entry to `## Done` with a one-line
summary citing the commit sha(s) and the IT fixture location.
