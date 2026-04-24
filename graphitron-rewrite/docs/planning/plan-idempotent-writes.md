# Plan: Content-idempotent writes + stale-file sweep

> **Status:** Spec
>
> Sub-item of the "Dissolve `graphitron-schema-transform` module"
> umbrella. Independent of the other sub-items; can ship against either
> the legacy plugin or the new rewrite-owned plugin. Landed here because
> the umbrella is where consumer-facing Maven-pipeline work lives; the
> behaviour shows up as "did my IDE recompile every file or just the
> ones I changed?" when a developer edits a schema.
>
> Ships a three-clause generator contract (determinism, minimal-change
> writes, clean removal) documented as an intended feature in
> `graphitron-rewrite/docs/getting-started.md`, not an internal
> optimization.

## Goal

Make the rewrite generator's output tree stable across runs: write a
file only when its bytes differ from what's already on disk, and delete
files that previous runs produced but this run did not. Post-landing,
unchanged files keep their mtimes, so IntelliJ's incremental compiler
and Quarkus live reload recompile only the types the schema edit
actually touched.

## Motivation

Generator writes land in `target/generated-sources/`, which IDEs and
dev-mode tools watch as a source root. Two failure modes today:

1. **Unchanged files get rewritten.** `JavaFile.writeTo(File)` (at
   `GraphQLRewriteGenerator.java:97`) unconditionally overwrites. Every
   file's mtime bumps on every generator run, so IntelliJ's incremental
   compiler and Quarkus live reload see every generated class as
   "changed" and recompile the lot. A schema edit that touches one type
   still triggers a full downstream recompile.
2. **Deleted schema elements leave orphan files.** Removing a type from
   the schema removes the emitter call, but the previous run's
   `FooType.java` stays on disk and keeps compiling against stale
   references until the developer manually clears `target/`.

Both failure modes compound on larger schemas (Sikt's production schema
emits ~400 files), where full recompiles dominate the inner dev loop.

## Developer contract

The behaviour this plan lands is a contract, not an optimization.
Post-landing, the generator guarantees three clauses developers can
rely on when building their dev-loop and CI setup:

1. **Determinism.** Same schema + same config → same bytes on disk.
   Running the generator twice produces byte-identical output trees,
   so there is no mtime churn and no spurious diff in version control
   between two consecutive runs over an unchanged input.
2. **Minimal-change writes.** A schema edit that touches one type
   rewrites that type's file (and its direct dependents when a
   signature changes); every other file lands as a no-op write, so
   IntelliJ's incremental compiler, Quarkus `quarkus:dev`, and Spring
   Boot DevTools recompile only what actually changed.
3. **Clean removal.** Removing a type or field from the schema deletes
   the corresponding generated file at the end of the next generator
   run. No orphan code, no stale compile errors against removed types,
   no manual `target/` cleanup.

Each clause has a named test ratchet (see §Tests):
`GeneratorDeterminismTest` pins (1), the mtime-preservation test pins
(2), the orphan-sweep tests pin (3). A future refactor that breaks any
clause fails a test; restoring the clause is not a judgment call.

The contract composes with standard IDE / build-tool incremental
compile paths. No Graphitron-specific IDE plugin, watch goal, or
opt-in flag is required; developers get the behaviour from using the
generator.

## Scope boundaries

- **In scope:** the single writer funnel at
  `GraphQLRewriteGenerator.java:90-103`. Content-idempotent write;
  end-of-run orphan sweep scoped to the sub-packages this generator
  emits. Determinism audit of the emitters that feed the funnel.
  A new `## Dev loop` section in `graphitron-rewrite/docs/getting-started.md`
  documenting the three-clause contract in developer-observable
  terms (see §Documentation).
- **Out of scope:** the legacy generator's write sites in
  `graphitron-java-codegen`. Legacy stays as-is; the umbrella retires
  it later. Parallel-safety of the write loop; the generator runs
  sequentially today and this plan keeps it that way.
- **Out of scope:** incremental *generation* (only regenerating the
  types the schema edit touched). The generator still walks the full
  schema every run; the plan just arranges for unchanged outputs to
  land as no-ops. Schema-delta-driven generation is strictly harder
  and its payoff is small once unchanged writes are free.

## Current state

One funnel, one writer call:

```java
// GraphQLRewriteGenerator.java:90-103
private static void write(List<TypeSpec> specs, String subPackage) {
    var packageName = subPackage.isEmpty()
        ? RewriteConfig.outputPackage()
        : RewriteConfig.outputPackage() + "." + subPackage;
    specs.forEach(spec -> {
        try {
            JavaFile.builder(packageName, spec).indent("    ").build()
                .writeTo(new File(RewriteConfig.outputDirectory()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    });
}
```

Called seven times from `generate()` (lines 80-87) with sub-packages
`""`, `"schema"`, `"types"`, `"conditions"`, `"fetchers"`. Every
generator TypeSpec reaches the filesystem through this one path. Nice.

## Design

### Content-idempotent write

Replace `JavaFile.writeTo(File)` with a three-step sequence:

1. Render the `JavaFile` to an in-memory `String` via
   `JavaFile.toString()` (javapoet already supports this; we avoid
   creating an intermediate file).
2. Compute the target `Path` from `outputDirectory + packageName +
   spec.name + ".java"`. This mirrors what `JavaFile.writeTo` would do.
3. Read the existing bytes if the file exists; if they match the
   rendered string (UTF-8), skip the write and record the path as
   "seen". Otherwise write the new content and record the path.

Record every path written or skipped in a `Set<Path> emittedThisRun`
carried by the generator run.

### Orphan sweep

After the seven `write(...)` calls in `generate()`, walk the
sub-packages this generator owns (`<outputPackage>`,
`<outputPackage>.schema`, `<outputPackage>.types`,
`<outputPackage>.conditions`, `<outputPackage>.fetchers`) rooted at
`outputDirectory`. Any `*.java` file not in `emittedThisRun` gets
deleted. Walk is non-recursive beyond the five named sub-packages;
other files in the output tree (e.g. jOOQ-generated tables,
legacy-generator output in coexistence mode) are off-limits.

Sweep scope is defined by the five sub-package constants inside the
generator, not by a recursive `Files.walk` from `outputDirectory`.
This keeps the sweep's blast radius narrow and easy to reason about.

### Determinism audit

Content-idempotent writes only help when identical inputs produce
identical bytes. Audit target: every collection iterated during
emission. Concretely:

- `Map` / `Set` literals used to build emitted content must iterate in
  a stable order. Prefer `LinkedHashMap` / `LinkedHashSet` where
  insertion order is meaningful; sort explicitly where the key is a
  schema identifier.
- Emitted-member order inside a `TypeSpec` (fields, methods,
  supertypes) must be deterministic; javapoet preserves insertion
  order, so the emitters' input must already be sorted or stably
  ordered.
- No system clock, no random UUIDs, no hash-code-derived ordering.
  Grep for `System.currentTimeMillis`, `Instant.now`, `UUID.randomUUID`,
  `Object::hashCode` in comparators.

The audit is a finding exercise. Fixes land case-by-case in the same
commit; each site is small. If the audit surfaces a non-trivial
emitter that relies on non-deterministic iteration, split that fix
into a follow-up and keep this plan focused on the writer change.

### Determinism ratchet

Add a `GeneratorDeterminismTest` that runs the full rewrite generator
twice against the same fixture schema and asserts the two output trees
are byte-identical. Catches any future emitter that introduces
non-determinism; the content-idempotent write is worthless without
this guarantee.

Runs against an existing `graphitron-rewrite-fixtures` schema
(whichever one already drives the pipeline tier); no new fixture
needed.

## Tests

Three tiers, matching the established unit / pipeline / integration
split:

1. **Unit: `IdempotentWriterTest`** (new, in `graphitron-rewrite`).
   - Skips the write when existing bytes match.
   - Overwrites when bytes differ.
   - Records the path in `emittedThisRun` regardless of which branch
     ran.
   - Sweep deletes files not in `emittedThisRun`; leaves in-set files
     alone; stays inside the five named sub-packages.

2. **Pipeline: `GeneratorDeterminismTest`** (new, in
   `graphitron-rewrite-test`). Byte-for-byte equal output trees across
   two runs of the full generator against the test fixture schema.
   This is the ratchet described in §Determinism audit.

3. **Integration: mtime preservation** (new, in
   `graphitron-rewrite-test`). Run the generator, capture mtimes of
   every emitted file, touch nothing, run it again, assert all mtimes
   unchanged. Protects against a future writer refactor re-introducing
   unconditional overwrites.

## Documentation

Lands in the same commit as the writer change: a new `## Dev loop`
H2 section in `graphitron-rewrite/docs/getting-started.md`, placed
between the existing `## Customizer safe surface` and `## Notes`
sections.

The guide's opening paragraph currently scopes out build-time
behaviour in its entirety ("Build-time Maven plugin configuration is
out of scope here"). This plan narrows that exclusion to plugin
*configuration* specifically; the runtime-visible dev-loop behaviour
is a documented feature of the generator and belongs in the same
guide that covers the runtime API, because it's part of what a
consumer agrees to when they pick up rewrite. Rewording the opening
paragraph is part of this deliverable.

Proposed section content (outline; prose lands in the commit):

- **What the developer does.** Edit `.graphqls` source; run
  `mvn generate-sources` (or let the surrounding build tool
  re-trigger it, for consumers who have their own watch / live-reload
  wiring). The plan ships no Graphitron-specific watch goal.
- **What the generator does.** Walks the full schema, renders each
  output file, writes only the files whose content changed, deletes
  files corresponding to removed schema elements. All three actions
  happen unconditionally; no flag to enable.
- **What the developer observes.** The three contract clauses in
  developer-observable terms: `git diff` shows only the types the
  schema edit touched; IDE recompile time is proportional to the
  changed set, not the full generated tree; removing a type or field
  from the schema removes the corresponding generated file on the
  next run.
- **Tool interop.** One-line confirmation that IntelliJ incremental
  compile, Quarkus `quarkus:dev`, and Spring Boot DevTools work with
  the behaviour out of the box; no Graphitron-specific tool
  integration is required.

Not in scope for the getting-started section: guidance on Maven-plugin
configuration (still deferred until the rewrite-owned plugin lands).
The new section is purely about the runtime-visible behaviour.

## Rollout

Single-commit landing. No consumer-side flag, no migration: the new
writer behaviour is strictly more correct than the old one, and
downstream consumers see only "my recompiles got faster".

One caveat: the first run after this lands may delete files that the
legacy generator emitted into rewrite's sub-packages while both ran
in coexistence. In practice this is scoped to `<outputPackage>.schema`
/ `.types` / `.conditions` / `.fetchers`, which are rewrite-owned
sub-packages; the legacy generator emits into different sub-packages
(`generated.resolvers.*`, `generated.queries.*`, etc.). Audit the
actual legacy sub-package layout in the "current state" snapshot of
a consumer repo before the landing commit and confirm no collision.

## Roadmap integration

Roadmap sub-item under "Dissolve `graphitron-schema-transform`
module". Independent of the schema-loading, tagged-inputs, and
Maven-plugin sub-items; ships whenever picked up. No sequencing
constraint either way.

On landing, move this plan's entry to `## Done` in the roadmap with a
one-line summary citing the commit sha and the
`GeneratorDeterminismTest` fixture location.
