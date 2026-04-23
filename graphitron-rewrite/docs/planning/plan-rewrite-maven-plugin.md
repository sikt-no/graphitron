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

Ship `graphitron-rewrite-maven-plugin` as a new Maven plugin artifact
with one primary goal (`generate`) and a minimal, opinionated config
surface. The plugin replaces static-config singletons with a
per-invocation context, drops every legacy escape hatch, and exposes
exactly the knobs rewrite consumers need today.

Driving principle: simplification. Every config element inherited from
`graphitron-maven-plugin` must justify its continued existence against
the concrete wart it papers over. Unjustified knobs get cut, not
migrated.

## Scope

**In scope**

- New artifact `graphitron-rewrite-maven-plugin` at
  `graphitron-rewrite/graphitron-rewrite-maven-plugin/`.
- One `generate` Mojo driving `GraphQLRewriteGenerator.generate()`
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
└── graphitron-rewrite-maven-plugin/         # NEW
    ├── pom.xml                              # packaging=maven-plugin
    └── src/main/java/no/sikt/graphitron/rewrite/maven/
        ├── GenerateMojo.java                # the one primary goal
        ├── RewriteContext.java              # per-invocation config
        └── SchemaInputBinding.java          # POM XML binding for <schemaInput>
```

Package `no.sikt.graphitron.rewrite.maven` (not `no.sikt.graphitron.mojo`)
to keep legacy-plugin class-search hits out of rewrite work.

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
    @Parameter private List<ExternalRefBinding> externalReferences;
    @Parameter private List<ScalarBinding> scalars;
    @Parameter(defaultValue = "1000") private int maxAllowedPageSize;

    @Override
    public void execute() {
        var ctx = RewriteContext.from(this, project);
        new GraphQLRewriteGenerator(ctx).generate();
    }
}
```

**That is the entire `@Parameter` list.** Eight parameters vs. the
legacy plugin's 18+ across its Mojos. Everything else from the legacy
audit is dropped (see §Current state).

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
    Map<String, String> externalReferences,
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

Exactly one: `generate`. Companion goals the legacy plugin ships are
re-evaluated against consumer pressure:

- `validate`: fold into `generate` as a `<skipCodeOutput>true</skipCodeOutput>`
  mode? Or drop entirely and let consumers run `mvn compile` on the
  output? See D3.
- `introspect`: rewrite has no jOOQ-introspection code today;
  consumers who need LSP config keep using `graphitron-maven-plugin:introspect`
  until a separate plan ports it. Not blocked by this plan.
- `transform`: gone. The only transformation the rewrite plugin
  does is `<schemaInputs>` tagging, which runs inside `generate`.
- `watch`: gone. IDE incremental builds (IntelliJ Maven project
  reimport on schema change) cover the use case; a Maven-goal file
  watcher is a dev-mode feature that doesn't justify the extra
  surface. See D4 if a consumer objects.

### Config bindings (POM → Java)

Three small POJOs that Maven populates from XML:

- `SchemaInputBinding`: fields `pattern`, `tag`, `descriptionNote`.
  Converted to `SchemaInput` (from the sibling plan) inside
  `RewriteContext.from(...)`.
- `ExternalRefBinding`: fields `name`, `className`. Replaces
  legacy's `ExternalMojoClassReference`, flattened (legacy has an
  `imports` list that was underused).
- `ScalarBinding`: fields `name`, `className`. Replaces legacy's
  reuse of `ExternalMojoClassReference` for scalar types.

Deliberate: one POJO per concept. No shared base class.

### Lifecycle and packaging

- `pom.xml` packaging: `maven-plugin`.
- `maven-plugin-plugin` generates `plugin.xml` from annotations.
- Default lifecycle binding: `GENERATE_SOURCES`, declarative via
  `@Mojo`. No `@Execute`.
- Minimum Maven version: 3.9 (matches project-wide `mise` env).
- Java: plugin code targets Java 21 (build-time); plugin output
  classes targeted at 17 (generator's existing output contract).

## Migration

Consumers currently running rewrite via `graphitron-maven-plugin`
with `<enableRewrite>true</enableRewrite>` + `<disableLegacy>true</disableLegacy>`
switch to the new plugin in one diff:

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
  </configuration>
</plugin>
```

After:
```xml
<plugin>
  <groupId>no.sikt.graphitron</groupId>
  <artifactId>graphitron-rewrite-maven-plugin</artifactId>
  <configuration>
    <schemaInputs>...</schemaInputs>
    <outputDirectory>...</outputDirectory>
    <outputPackage>...</outputPackage>
    <jooqPackage>...</jooqPackage>
  </configuration>
</plugin>
```

Parameter renames (kept intentional; the legacy names carried the
warts):
- `outputPath` → `outputDirectory` (match standard Maven naming).
- `jooqGeneratedPackage` → `jooqPackage` (shorter; less
  implementation-detail).
- `schemaFiles` / `userSchemaFiles` → `schemaInputs` (sibling plan).

No migration for consumers still on legacy-only (`enableRewrite=false`).
They keep using `graphitron-maven-plugin` until the legacy retirement
landing marker fires.

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

**D1. Artifact name.** `graphitron-rewrite-maven-plugin` is descriptive
but long. Alternatives: `graphitron-plugin` (short, but collides
conceptually with the legacy one during the coexistence window);
`graphitron-rewrite-plugin` (Maven convention prefers the
`-maven-plugin` suffix for goal prefix inference). Recommend
`graphitron-rewrite-maven-plugin`; consumers get the short goal prefix
`graphitron-rewrite:generate`.

**D2. Keep `externalReferences` + `scalars` as Mojo `@Parameter` fields?**
Legacy plugin's namedReference + ScalarConfig shapes are
generator-internal leaks (the consumer writes config in terms of the
generator's own extension point types). Alternative: move to a
resource file (`graphitron.yaml` or `graphitron.json` at project root)
and make the Mojo config one line (`<configFile>`). Cleaner; one-way
door though; switching later is a breaking change. Recommend keeping
as `@Parameter` for now; revisit if a third generator-extension
concept lands and the Mojo grows bloated.

**D3. `validate` goal.** Options: (a) drop entirely, (b) add as a
thin wrapper that runs `GraphitronSchemaValidator` without writing
output, (c) fold into `generate` as `<skipWrite>true</skipWrite>`.
Recommend (a): `mvn compile` on the generated output is a stricter
validator than the schema-validator today, and the schema-validator
runs inside `generate` anyway. Re-add if a consumer use case emerges.

**D4. `watch` goal.** Dropped in the design (see §Design: Goals). If
a consumer objects, cheapest revival: a `watch` goal that re-runs
`generate` on schema-file change, wrapping the same `GenerateMojo`
logic. Not worth spec'ing until asked.

**D5. `@Parameter` defaults for `outputPackage` / `jooqPackage`.**
Legacy defaults `outputPackage` to `no.sikt.graphql`: opinionated
about Sikt-ness. The new plugin could drop the default (require
explicit), ship with a Sikt-flavoured default, or read from
`project.groupId + ".graphql"`. Recommend require-explicit; consumers
know their package better than the plugin does, and the required-
parameter error is clear.

**D6. Java release target.** Plugin code itself is Java 21 (generator
is). The generated output is Java 17, pinned by the generator's own
`release=17` ratchet (per CLAUDE.md). No plugin-level knob needed;
call this out explicitly in the plan so it doesn't resurface as a
feature request.

## Roadmap integration

New sub-item on the "Dissolve `graphitron-schema-transform` module"
umbrella, inserted immediately after "Rewrite owns schema loading +
directive auto-injection" (the prerequisite) and before
"Rewrite owns type-extension merging":

```
- **Rewrite-owned Maven plugin** **[Spec]** — new
  `graphitron-rewrite-maven-plugin` artifact with one `generate`
  goal and a per-invocation `RewriteContext` replacing the
  `GeneratorConfig` + `RewriteConfig` static singletons. Eight
  `@Parameter` fields vs. legacy's 18+; no legacy gating toggles,
  no `@Execute` trickery, no embedded transform logic. Drops
  `validate` / `transform` / `watch` goals by default (re-added
  only on demand). ([plan](plan-rewrite-maven-plugin.md))
```

The `<schemaInputs>` plan's D1 (replace vs. coexist with
`<schemaFiles>`) is resolved by this plan: the new plugin has no
`<schemaFiles>`, so the coexist option evaporates.

On landing, move this plan's entry to `## Done` with a one-line
summary citing the commit sha(s) and the IT fixture location.
