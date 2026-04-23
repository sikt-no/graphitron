# Plan: Rewrite-owned Maven plugin

> **Status:** Spec
>
> Sub-item of the "Dissolve `graphitron-schema-transform` module"
> umbrella. Introduces a new, rewrite-only Maven plugin artifact that
> starts from a clean config schema and a per-invocation context
> object, leaving `graphitron-maven-plugin`'s organic-growth warts
> behind entirely.
>
> Drives simplification for the surrounding umbrella work: the
> `<schemaInputs>` plan, the `<outputSchemas>` plan, and the eventual
> retirement of `graphitron-schema-transform` all land on this plugin,
> not on the legacy one.

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
  via a per-invocation context object (no static singletons).
- Minimal config schema, anchored on `<schemaInputs>` (sibling plan).
- Plugin packaging, lifecycle bindings, `plugin.xml` descriptor,
  integration test harness.
- Migration documentation for consumers already running rewrite via
  `graphitron-maven-plugin`'s `enableRewrite` flag.

**Out of scope**

- Implementing `<schemaInputs>` resolver + appliers (covered by
  [plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md);
  this plugin consumes the resolver, doesn't own it).
- Implementing `<outputSchemas>` (future umbrella sub-item; plugin
  grows an element when that plan ships).
- Decommissioning `graphitron-maven-plugin` (the umbrella's "Retire
  `graphitron-schema-transform`" landing marker; legacy plugin keeps
  running until then).
- Any classifier / emitter / validator refactor inside
  `graphitron-rewrite` proper. This plan is strictly the Maven-plugin
  boundary and the config object that flows through it.

## Current state: what we're leaving behind

Audit of `graphitron-maven-plugin` surfaced seven concrete warts that
the new plugin does not inherit. Each is tied to a file + line in the
legacy code so the cut is unambiguous.

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
   vs. `GenerateMojo.userSchemaFiles` (user-provided). The sibling
   `<schemaInputs>` plan subsumes both.

4. **Rewrite / legacy gating toggles.** `enableRewrite` (default
   `false`) and `disableLegacy` (default `false`) at `GenerateMojo.java:114,123`
   gate which generator runs. `ValidateMojo.failOnRewriteValidationError`
   (default `true`) lets consumers downgrade rewrite errors. The new
   plugin is rewrite-only; no gates.

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

### `RewriteContext`: replaces static config

Immutable record + derived fields, constructed once per Mojo
execution. Replaces `GeneratorConfig` + `RewriteConfig` for the
rewrite path.

```java
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Path outputDirectory,
    String outputPackage,
    String jooqPackage,
    Map<String, String> namedReferences,
    List<ScalarMapping> scalars,
    int maxAllowedPageSize
) {
    static RewriteContext from(GenerateMojo mojo, MavenProject project) { ... }
}
```

Passed by constructor into `GraphQLRewriteGenerator`, which threads it
down rather than reading statics. No thread-safety concerns, no
two-stage population, no subset leak.

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

Three small POJOs that Maven populates from XML:

- `SchemaInputBinding`: fields `pattern`, `tag`, `descriptionNote`.
  Converted to `SchemaInput` (from the sibling plan) inside
  `RewriteContext.from(...)`.
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
| `<schemaFiles>` / `<userSchemaFiles>` | `<schemaInputs>` | sibling plan |
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

New sub-item on the "Dissolve `graphitron-schema-transform` module"
umbrella, inserted immediately after "Rewrite owns schema loading +
directive auto-injection" (the prerequisite) and before
"Rewrite owns type-extension merging":

```
- **Rewrite-owned Maven plugin** **[Spec]** — new
  `graphitron-rewrite-maven` artifact with `generate` + `validate`
  goals and a per-invocation `RewriteContext` replacing the
  `GeneratorConfig` + `RewriteConfig` static singletons. Eight
  `@Parameter` fields vs. legacy's 18+; no legacy gating toggles,
  no `@Execute` trickery, no embedded transform logic. Drops
  `transform` + `watch` goals; `introspect` ports separately
  (sibling item). ([plan](plan-rewrite-maven-plugin.md))
```

The `<schemaInputs>` plan's D1 (replace vs. coexist with
`<schemaFiles>`) is resolved by this plan: the new plugin has no
`<schemaFiles>`, so the coexist option evaporates.

On landing, move this plan's entry to `## Done` with a one-line
summary citing the commit sha(s) and the IT fixture location.
