---
id: R247
title: Emit assembled schema.graphqls into generated-resources/graphitron, federation-aware
status: In Review
bucket: feature
depends-on: []
created: 2026-05-27
last-updated: 2026-05-28
---

# Emit assembled schema.graphqls into generated-resources/graphitron, federation-aware

## Redesign (Ready → Spec, 2026-05-27)

The first implementation pass (commit `8c7e101`) landed faithfully against
the spec as written but introduced a new Mojo `@Parameter
outputResourcesDirectory`. That contradicts the "no Mojo flag, no
per-consumer toggle, no configurable filename" line below and adds a knob
nobody needs to turn. The redesign drops the parameter while keeping the
Maven-convention layout: the file lands at
`${project.build.directory}/generated-resources/graphitron/<outputPackage
as path>/schema.graphqls`, derived inside the Mojo from
`project.getBuild().getDirectory()` (not user-configurable, hardcoded
relative segment `generated-resources/graphitron`). `GenerateMojo`
registers that directory via `project.addResource(...)` so the
maven-resources-plugin copies the tree into `target/classes` at the
`process-resources` phase, and the file ships at
`<outputPackage as path>/schema.graphqls` in the consumer's JAR.

The path still needs to thread from the Mojo down to the emitter, so
`RewriteContext.outputResourcesDirectory` stays as a record component; it
just becomes a Mojo-derived field instead of a user-bound `@Parameter`.

Implementation commits to revert / amend in the next pass (single revert
plus fresh emission commit is cleanest):

- `8c7e101` — drop the `outputResourcesDirectory` `@Parameter` declaration
  on `AbstractRewriteMojo` and the `GenerateMojoTest` field-setter
  additions. Replace the `Path.of(outputResourcesDirectory)` resolution
  step with a hardcoded
  `Path.of(project.getBuild().getDirectory()).resolve("generated-resources/graphitron")`
  derivation in `AbstractRewriteMojo.buildContext`. Keep the
  `RewriteContext.outputResourcesDirectory` field, the
  `SchemaSdlEmitter` dispatch + path logic, and the test shape.

See **Implementation sites** below for the revised mechanics.

---

> Add one step to the rewrite pipeline that prints the assembled
> `GraphQLSchema` to a `schema.graphqls` file under
> `${project.build.directory}/generated-resources/graphitron/<outputPackage
> as path>/`, so the file ships on the classpath at
> `<outputPackage as path>/schema.graphqls` like a regular Java resource,
> alongside the generated `Graphitron.class` facade. When the schema
> carries the Apollo Federation `@link`, route through
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
3. **Output location:
   `${project.build.directory}/generated-resources/graphitron/<outputPackage
   as path>/schema.graphqls`.** Mirrors the Maven convention for
   plugin-generated resources (`generated-resources/<plugin-name>/`), so
   the file ends up at e.g. `com/example/myapp/schema.graphqls` on the
   classpath after maven-resources-plugin's `process-resources` phase
   copies it into `target/classes/`, alongside the generated
   `Graphitron.class` facade. A consumer that wants the schema at runtime
   calls `getClass().getResourceAsStream("schema.graphqls")` from any
   class in `outputPackage`; the lookup is package-local, so two graphitron
   consumers in the same JVM can each find their own. The relative path
   `generated-resources/graphitron` is hardcoded inside the Mojo (no
   `@Parameter`); only the build directory itself comes from Maven
   (`project.getBuild().getDirectory()`), the same source the existing
   `outputDirectory` default resolves against.
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
constructor. The two convenience constructors at `:76` and `:95` (used
only by unit-tier callers) derive a sensible default from
`outputDirectory` (a sibling named `generated-resources-graphitron` is
fine: unit-tier doesn't run through Maven, so the literal Maven layout
is moot there). The generator reads the field directly when writing the
SDL file. The shape from `8c7e101` is correct here; only the
user-configurability story changes (see site 2).

### 2. `AbstractRewriteMojo`: derive (not parameterise) the resources root

`graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/AbstractRewriteMojo.java`

Do **not** add a `@Parameter` for the resources directory. In
`buildContext(...)`, compute the path inline from the Maven build
directory, with a `basedir/target` fallback for hand-built `MavenProject`
instances (test fixtures call `new MavenProject()` without
`setBuild(...)`, so `project.getBuild()` returns null until the lifecycle
populates it):

```java
var buildDirectory = project.getBuild() != null
    ? project.getBuild().getDirectory()
    : null;
var targetDir = buildDirectory != null
    ? Path.of(buildDirectory)
    : basedir.resolve("target");
var resourcesAbs = (targetDir.isAbsolute() ? targetDir : basedir.resolve(targetDir))
    .resolve("generated-resources/graphitron")
    .normalize();
```

Pass `resourcesAbs` into the `RewriteContext` constructor in the slot
that lands at `outputResourcesDirectory`. The relative segment
`generated-resources/graphitron` is a hardcoded Maven convention
(`generated-resources/<plugin-name>/`) and not user-tunable, so no Mojo
flag, no doc-row, no `MojoDocCoverageTest` drift, and the hand-built
mojos in `DevMojoTest` / `CodegenLoaderTest` / `GenerateMojoTest` need
no field-setter changes. The `getBuild() == null` fallback also keeps
`8c7e101`'s `GenerateMojoTest` modifications from being load-bearing:
back them out as part of the rework.

### 3. `GenerateMojo`: register the resource root with Maven

`graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/GenerateMojo.java:29`

After `project.addCompileSourceRoot(outputDirectory);` add an
`org.apache.maven.model.Resource` with its `directory` set to the
absolute path of the derived `generated-resources/graphitron` root
(computed the same way as in `buildContext`, or recovered from
`ctx.outputResourcesDirectory()` after `runGenerator` returns), and
call `project.addResource(resource)`. This is the one-line "good Java
citizen" wiring: the generated-resources tree gets copied into
`target/classes` at the `process-resources` phase, and the file ships
in the jar at `<outputPackage as path>/schema.graphqls`.

### 4. New emitter: `SchemaSdlEmitter`

`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitter.java`

Single static `emit(GraphQLSchema assembled, boolean federationLink,
Path resourcesRoot, String outputPackage)` method (parameter name
matches `RewriteContext.outputResourcesDirectory()`, but inside the
emitter "resourcesRoot" reads better):

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

### Pipeline ↔ runtime parity (property test)

In `graphitron-sakila-example`, add a test that asserts the emitted
`schema.graphqls` on the classpath is **byte-identical** to the result of
printing the runtime-built schema through the same printer the emitter
would use for that federation arm:

```java
@Test
void emittedSdlMatchesRuntimePrint() throws IOException {
    String emitted;
    try (var in = Graphitron.class.getResourceAsStream("schema.graphqls")) {
        assertThat(in).as("emitted schema.graphqls on classpath").isNotNull();
        emitted = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    GraphQLSchema schema = Graphitron.buildSchema(b -> {}, fed -> {});
    String runtimePrint = ServiceSDLPrinter.generateServiceSDLV2(schema);
    assertThat(emitted).isEqualTo(runtimePrint);
}
```

The printer arm is whichever `Bundle.federationLink()` resolves to for
the sakila fixture (federation is in use in the sakila example today,
so `ServiceSDLPrinter`; if a non-federation fixture is added later the
test pairs against `SchemaPrinter` with the same options
`SchemaSdlEmitter.printPlain` uses).

This pins the strongest invariant the spec can carry: the on-classpath
SDL is exactly what a consumer reconstructing the schema at runtime
would print. It catches:

- Wrong printer at emission (`SchemaPrinter` where `ServiceSDLPrinter`
  was needed, or vice-versa) ; the two arms produce visibly different
  shapes for federation types.
- Non-determinism in schema build, which would make the shipped file
  drift from any runtime regeneration consumers run.
- Encoding or trailing-newline drift between `Files.writeString` and the
  printer's output.

Byte-equality implies the emitter and the printer's raw output must
agree on terminating-newline behaviour. If the printer omits a final
newline and `Files.writeString` does not add one, the test passes
naturally; if either side ever appends one, both must, and `SchemaSdlEmitter`
is the right place to normalise.

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
