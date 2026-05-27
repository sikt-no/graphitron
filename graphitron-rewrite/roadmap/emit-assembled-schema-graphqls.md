---
id: R247
title: Emit assembled schema.graphqls into generated-resources, federation-aware
status: Ready
bucket: feature
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Emit assembled schema.graphqls into generated-resources, federation-aware

## Review (In Review → Ready, 2026-05-27)

Implementation at `8c7e101` delivers the shape the spec describes
(`SchemaSdlEmitter` at the tail of `runPipeline`, dispatch on
`Bundle.federationLink()`, `@Parameter outputResourcesDirectory` on the
Mojo, resource root registered via `project.addResource`, defaulted on the
two `RewriteContext` convenience constructors, unit + pipeline-tier
coverage). What blocks approval is build-green: `mvn install -Plocal-db`
fails in `graphitron-maven-plugin` with three R247-induced regressions.

Findings:

1. `DevMojoTest.bindToTakenPortFailsWithOverrideHint`
   (`graphitron-rewrite/graphitron-maven-plugin/src/test/java/no/sikt/graphitron/rewrite/maven/DevMojoTest.java:90`)
   hand-builds a `DevMojo` and never sets `outputResourcesDirectory`. The
   new `Path.of(outputResourcesDirectory)` in `AbstractRewriteMojo.buildContext`
   (`AbstractRewriteMojo.java:108`) NPEs before the dev-port validation runs,
   so the test sees `NullPointerException` instead of the expected
   `MojoExecutionException`. The implementation commit's own note acknowledges
   this risk for `GenerateMojoTest` ("the @Parameter default only applies
   under Maven") but missed `DevMojoTest`. Fix: set
   `mojo.outputResourcesDirectory = basedir.resolve("target/generated-resources/graphitron").toString();`
   in `mojoFor` alongside the existing `outputDirectory` line.

2. `CodegenLoaderTest.codegenLoader_resolvesClassFromProjectCompileClasspath`
   (`graphitron-rewrite/graphitron-maven-plugin/src/test/java/no/sikt/graphitron/rewrite/maven/CodegenLoaderTest.java:105`)
   hits the same NPE for the same reason. Fix: set `outputResourcesDirectory`
   on the hand-built mojo in the `mojo(...)` helper.

3. `MojoDocCoverageTest.everyMojoParameterHasADocRowAndViceVersa`
   (`MojoDocCoverageTest.java:111`) fails because `outputResourcesDirectory`
   is a new editable parameter in `plugin.xml` without a matching row in
   `docs/manual/reference/mojo-configuration.adoc`. That doc guard is the
   project's gate against parameter/doc drift; spec section 2 implicitly
   pulls in a doc-row obligation (any new `@Parameter` needs one). Fix: add
   a row to the "Common parameters" table at
   `docs/manual/reference/mojo-configuration.adoc:37-70` describing
   `outputResourcesDirectory`, its default
   `${project.build.directory}/generated-resources/graphitron`, and that
   `generate` registers the directory via `project.addResource` so
   `schema.graphqls` ships on the classpath under `<outputPackage>`.

Minor (non-blocking) observations:

- The `In Progress → In Review` commit (`744fe65`) message says
  "Implementation landed at 1d10159" but the actual implementation SHA is
  `8c7e101`. Stale message; cosmetic only.
- The spec body was not marked up to reflect shipped phases (spec section
  9 in `workflow.adoc` describes the "collapse to one-line shipped at `<sha>`"
  hygiene). Acceptable for a single-commit landing.
- Spec section 1 said "thread `outputResourcesDirectory` through the two
  convenience constructors"; the implementation defaults it to a
  `generated-resources-graphitron` sibling instead. This is a benign
  divergence (preserves unit-tier ergonomics) and is fine as shipped.

Once the three failing tests + the doc row are in, re-run
`mvn -f graphitron-rewrite/pom.xml install -Plocal-db` to verify green,
then flip back to In Review for a fresh reviewer session.

---

> Add one step to the rewrite pipeline that prints the assembled
> `GraphQLSchema` to a `schema.graphqls` file under the consumer's
> `outputPackage` in a generated-resources root, so the file ships on the
> classpath alongside the generated Java like a regular Java resource.
> When the schema carries the Apollo Federation `@link`, route through
> `com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter`
> (already a runtime dep, pinned at `6.0.0`); otherwise use graphql-java's
> `SchemaPrinter` with a directive-aware configuration. The decision is
> driven by `Bundle.federationLink()`, which the pipeline already computes;
> no Mojo flag, no per-consumer toggle, no configurable filename.

---

## Motivation

Downstream CI pipelines (supergraph composition, schema publication,
contract diffing) need a single resolved `schema.graphqls` artifact, but
graphitron-rewrite emits Java only. The plugin's input-side `*.graphqls`
files are pre-assembly fragments (per-file `extend type` declarations,
no resolved federation `@link`s, no `@asConnection`-synthesised
connection types). Consumers either reconstruct the schema at runtime
from `GraphitronSchema.build()` or hand-maintain a published copy
alongside the generated code; neither composes cleanly into a CI step
that needs the SDL as a build output.

R248 (commit `a2b1705`) materially de-risks this work by fixing the
assembled programmatic schema so it round-trips through
`ServiceSDLPrinter`: `DirectiveDefinitionEmitter` now carries argument
defaults, and federation-namespace scalars (e.g. `federation__FieldSet`)
register under their SDL name via the new `ScalarResolution.Synthesised`
arm rather than collapsing into `Scalars.GraphQLString`. The
pipeline-tier assertion at `FederationBuildSmokeTest.java:125-133`
(`graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java`)
is the working reference: it calls `Graphitron.buildSchema(...)` then
`ServiceSDLPrinter.generateServiceSDLV2(schema)` and pattern-matches the
canonical `@key(fields: federation__FieldSet!, ...)` shape plus
`scalar federation__FieldSet`. R247's pipeline step models its
federation branch directly on that call site.

## Design decisions

1. **Print the assembled `GraphQLSchema`, not the input fragments.** The
   assembled form is what subgraph consumers actually need; it carries
   `@asConnection` synthesis, the resolved federation `@link`, and any
   directive rewriting. R248 demonstrated the round-trip is correct.
2. **Filename is fixed: `schema.graphqls`.** No Mojo parameter, no
   per-consumer override. One name, one place, predictable for tooling.
3. **Output location: `<outputResourcesDirectory>/<outputPackage as
   path>/schema.graphqls`.** Mirrors the Java side (`outputDirectory` +
   `outputPackage`), so the file ends up at e.g.
   `com/example/myapp/schema.graphqls` on the classpath, alongside the
   generated `Graphitron.class` facade. A consumer that wants the
   schema at runtime calls
   `getClass().getResourceAsStream("schema.graphqls")` from any class in
   `outputPackage`; the lookup is package-local, so two graphitron
   consumers in the same JVM can each find their own.
4. **Federation detection: `Bundle.federationLink()`.** The boolean is
   already computed by `FederationLinkApplier.apply` and threaded
   through `GraphitronSchemaBuilder.buildBundle`
   (`GraphitronSchemaBuilder.java:96`,
   `GraphQLRewriteGenerator.java:167`). True → `ServiceSDLPrinter`.
   False → `SchemaPrinter`. No reflective `@link` inspection, no extra
   Mojo flag.
5. **One pipeline step, not a separate Mojo.** The emission belongs in
   `GraphQLRewriteGenerator.runPipeline` next to the existing Java
   `write(...)` calls. There is no use case for emitting the SDL
   without the Java (or vice versa); coupling them in one pipeline is
   simpler and matches how every other artifact is generated.

## Implementation sites

The work is a small fan-out across the plugin, the context, the
generator, and one new emitter class.

### 1. `RewriteContext`: add a resources-output slot

`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/RewriteContext.java`

Add `Path outputResourcesDirectory` as a record component alongside the
existing `outputDirectory`. `Objects.requireNonNull` it in the canonical
constructor; thread it through the two convenience constructors at
`:76` and `:95`. The generator reads it directly when writing the SDL
file.

### 2. `AbstractRewriteMojo`: `@Parameter` plus wiring

`graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/AbstractRewriteMojo.java`

Add:

```java
@Parameter(defaultValue = "${project.build.directory}/generated-resources/graphitron")
String outputResourcesDirectory;
```

In `buildContext(...)`, resolve it to an absolute `Path` (same
`isAbsolute` / `basedir.resolve` pattern used for `outputDirectory` at
`:103-104`) and pass it into the `RewriteContext` constructor.

### 3. `GenerateMojo`: register the resource root with Maven

`graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/GenerateMojo.java:29`

After `project.addCompileSourceRoot(outputDirectory);` add an
`org.apache.maven.model.Resource` with its `directory` set to the
absolute path of `outputResourcesDirectory`, and call
`project.addResource(resource)`. This is the one-line "good Java
citizen" wiring: the generated-resources tree gets copied into
`target/classes` at the `process-resources` phase, and the file ships
in the jar at `<outputPackage as path>/schema.graphqls`.

### 4. New emitter: `SchemaSdlEmitter`

`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitter.java`

Single static `emit(GraphQLSchema assembled, boolean federationLink,
Path resourcesRoot, String outputPackage)` method:

- Compute the target path:
  `resourcesRoot.resolve(outputPackage.replace('.', '/')).resolve("schema.graphqls")`.
- Create parent directories.
- Render the SDL:
  - `federationLink == true` →
    `ServiceSDLPrinter.generateServiceSDLV2(assembled)`.
  - `federationLink == false` → a `SchemaPrinter` configured with
    `includeDirectives(true)`, `includeScalarTypes(true)`,
    `includeIntrospectionTypes(false)`, `includeSchemaDefinition(true)`
    (mirror what `ServiceSDLPrinter` does on its end so the
    non-federation branch is structurally comparable).
- Write the file with `Files.writeString(..., StandardCharsets.UTF_8)`.
- Return the `Path` so the caller can include it in `emittedThisRun`
  (see point 5).

The class lives under `generators.schema` next to the other
schema-emitting generators (`GraphitronSchemaClassGenerator`,
`ObjectTypeGenerator`, ...) so the package locality matches its role.

### 5. `GraphQLRewriteGenerator.runPipeline`: invoke

`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphQLRewriteGenerator.java:163-206`

After the existing `write(...)` calls (anywhere after `assembled` is in
scope), add:

```java
emittedThisRun.add(SchemaSdlEmitter.emit(
    assembled, federationLink, ctx.outputResourcesDirectory(), outputPackage));
```

### 6. Orphan sweep

`GraphQLRewriteGenerator.sweepOrphans` at `:225-249` scans
`outputDirectory` for stale `.java` files. The `schema.graphqls` lives
in the *resources* tree, which the existing sweep doesn't visit, so
the existing sweep won't accidentally delete it. No change required.
A future iteration could add a parallel sweep for the resources tree,
but with only one file at one fixed path the failure mode (an old
`schema.graphqls` lingering if `outputPackage` changes) is small and
self-correcting on a clean.

## Tests

### Pipeline-tier

`FederationBuildSmokeTest` is the natural home for the federation arm:
extend it (or add a sibling `SchemaSdlEmissionTest`) with two cases.

1. **Federation case.** A fixture schema that triggers the federation
   `@link`. Assert:
   - The file exists at the expected path.
   - It contains `directive @key(fields: federation__FieldSet!, ...)`
     (the post-rename canonical shape R248 introduced).
   - It contains `scalar federation__FieldSet`.
   - It contains the `@link` declaration referencing
     `FederationSpec.URL`.
   These are the same assertions R248's smoke test runs against the
   in-memory `ServiceSDLPrinter` output; this test moves them down to
   the emitted file so a regression in the emitter (wrong path, wrong
   printer, encoding) fails the build.

2. **Non-federation case.** A fixture schema without `@link`. Assert:
   - The file exists at the expected path.
   - It is parseable by `graphql.schema.idl.SchemaParser`.
   - It does *not* contain `federation__FieldSet` or the federation
     `@link` URL (negative assertions guard against accidentally always
     routing through `ServiceSDLPrinter`).

### Unit

`SchemaSdlEmitterTest` covering the printer-selection branch directly
(a tiny hand-built `GraphQLSchema` with and without the federation
`@link` directive, no fixture-build needed). Keeps the dispatch logic
covered without paying the pipeline-tier cost.

### Sakila example smoke

`graphitron-sakila-example` already runs the full generator and
exercises classpath loading. Add a tiny execute-tier assertion (or fold
into an existing one) that loads
`<outputPackage>/schema.graphqls` via
`Thread.currentThread().getContextClassLoader().getResource(...)` and
asserts non-null, demonstrating the resource shipped in the jar.

## Out of scope

- Making the filename configurable (the user has explicitly ruled this
  out; one name, one place).
- Emitting multiple variants (e.g. a "stripped" non-federation copy
  alongside the federation copy, or per-tenant subgraph splits).
  Future items can layer that on; today's consumers want one file.
- Cross-module schema diff / contract validation tooling. R98
  (`multi-source-input-validation`) and the broader "knowledge base
  programme" (R117) cover that surface; R247 just produces the input.
- Retiring the in-process assembled-schema rebuild. R10
  (`drop-assembled-schema-rebuild`) is the tangent; R247 builds on
  whatever assembled form the pipeline produces at the time it runs,
  and will continue to work if R10 changes the rebuild's plumbing
  without changing its output shape.
