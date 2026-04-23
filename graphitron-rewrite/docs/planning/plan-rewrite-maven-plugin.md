# Plan: Rewrite-owned Maven plugin

> **Status:** Spec
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
- Plugin packaging, lifecycle bindings, `plugin.xml` descriptor,
  integration test harness.
- Expansion of the minimal `RewriteContext` (introduced by
  tagged-inputs with `schemaInputs` + `basedir`) with the remaining
  plugin knobs: output paths, packages, named references, scalars,
  page-size cap.
- Deletion of `RewriteConfig` statics entirely once all readers
  migrate to `RewriteContext`.
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
legacy code so the cut is unambiguous. Warts 4 and part of 5
(`ValidateMojo.failOnRewriteValidationError`, `enableRewrite` branch)
are already gone by the time this plan runs; tagged-inputs removed
them. Listed here for the full historical justification.

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
   Already deleted by tagged-inputs. The new plugin is rewrite-only
   from day one; no gates.

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
├── graphitron-rewrite/                      # unchanged: pipeline code
├── graphitron-rewrite-fixtures/             # unchanged
├── graphitron-rewrite-test/                 # unchanged
└── graphitron-rewrite-maven/                # NEW
    ├── pom.xml                              # packaging=maven-plugin
    └── src/main/java/no/sikt/graphitron/rewrite/maven/
        ├── GenerateMojo.java                # primary goal
        ├── ValidateMojo.java                # validate-only goal
        ├── RewriteContext.java              # per-invocation config
        └── SchemaInputBinding.java          # POM XML binding for <schemaInput>
```

Artifact: `graphitron-rewrite-maven` (non-standard Maven-plugin
suffix; see §Goal-prefix note at the end of this section). Package:
`no.sikt.graphitron.rewrite.maven`.

### `GenerateMojo` shape

```java
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES,
      requiresDependencyResolution = ResolutionScope.COMPILE)
public class GenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter private List<SchemaInputBinding> schemaInputs;
    @Parameter(defaultValue = "${project.build.directory}/generated-sources")
    private String outputDirectory;
    @Parameter(required = true) private String outputPackage;
    @Parameter(required = true) private String jooqPackage;
    @Parameter private List<NamedReferenceBinding> namedReferences;
    @Parameter private List<ScalarBinding> scalars;
    @Parameter(defaultValue = "1000") private int maxAllowedPageSize;

    @Override
    public void execute() {
        var ctx = RewriteContext.from(this, project);
        new GraphQLRewriteGenerator(ctx).generate();
    }
}
```

**That is the entire `@Parameter` list for `generate`.** Eight
parameters vs. the legacy plugin's 18+ across its Mojos. `ValidateMojo`
inherits the same eight via a small shared abstract base
(`AbstractRewriteMojo`); it omits nothing because schema loading +
`<schemaInputs>` resolution need the full input set. Everything else
from the legacy audit is dropped (see §Current state).

### `RewriteContext`: expanded to carry full plugin config

Tagged-inputs already put `RewriteContext` in
`no.sikt.graphitron.rewrite` with two fields (`schemaInputs`,
`basedir`). This plan expands the record with the remaining knobs;
the generator signature stays `new GraphQLRewriteGenerator(ctx)`
across both landings.

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
) {
    public static RewriteContext from(GenerateMojo mojo, MavenProject project) {
        return new RewriteContext(
            mojo.schemaInputs.stream().map(SchemaInputBinding::toSchemaInput).toList(),
            project.getBasedir().toPath(),
            Path.of(mojo.outputDirectory),
            mojo.outputPackage,
            mojo.jooqPackage,
            toNamedReferenceMap(mojo.namedReferences),
            toScalarMappings(mojo.scalars),
            mojo.maxAllowedPageSize
        );
    }
}
```

The `SchemaInputBinding.toSchemaInput()` converter is owned by this
plan (binding lives in the plugin module, record lives in rewrite
core, the conversion happens at the plugin boundary). Small static
helpers for named-references and scalars live inside
`RewriteContext`. `RewriteConfig` deletes once all readers migrate;
no new statics are introduced.

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
  Carries a `toSchemaInput()` method that returns a rewrite-core
  `SchemaInput` record; this is the only bridge between the plugin
  module and rewrite core for schema-input config.
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

### Lifecycle and packaging

- `pom.xml` packaging: `maven-plugin`.
- `maven-plugin-plugin` generates `plugin.xml` from annotations.
- Default lifecycle binding: `GENERATE_SOURCES`, declarative via
  `@Mojo`. No `@Execute`.
- Minimum Maven version: 3.9 (matches project-wide `mise` env).
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
| `<scalars>` (reuses ExternalMojoClassReference) | `<scalars>` (`<scalar><scalarName/><className/>`) | dedicated POJO; `scalarName` replaces reused `name` |
| `<transform>` (nested block)      | (removed)            | tagging now via `<schemaInputs>` |
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

Uses the Maven Invoker Plugin (standard for Maven-plugin ITs). A
minimal consumer `pom.xml` + `schema.graphqls`; running
`mvn graphitron-rewrite:generate` produces expected Java files at
`target/generated-sources/.../Graphitron.java`.

One IT covers the happy path; a second asserts that omitting
`<schemaInputs>` fails with a precise message (not an NPE).

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
