---
id: R410
title: "graphitron:dev owns incremental compilation of generated sources"
status: Ready
bucket: feature
priority: 3
theme: lsp
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# graphitron:dev owns incremental compilation of generated sources

## In one paragraph

`mvn graphitron:dev` today generates Java sources and stops: it writes `.java` under
`target/generated-sources/graphitron` through the idempotent writer and relies on *something else*,
the Quarkus dev environment, Spring Boot DevTools, or IntelliJ's incremental compiler, to turn those
sources into `.class`. On a real subgraph of thousands of generated files that hand-off is the slow
part of the loop: a full compile is minutes, not seconds, and the external tools recompile far more
than the edit actually touched. But we author the generated code, so we know its reference graph
exactly rather than having to infer it. This item makes `graphitron:dev` own the generated-source
compile step, driving an in-process compiler over a **model-sourced** dependency graph so that a
one-field schema edit recompiles the affected sub-closure (a handful of files) regardless of subgraph
size. That graph is a file-level coarsening of the same structure R333 models as the back half of the
lowering (a referentially-closed graph of emitted Java methods); the sourcing seam is built so it
re-sources from R333's method graph once that graph exists, and the coverage obligation that keeps it
one model (not a revived parallel structure) is a compile-checked exhaustive switch that is live from
day one, not a stub promised in prose.

## Scope boundary

Two boundaries keep this item finite and keep it from re-implementing a framework.

- **Compile, not hot-reload.** This owns generated `.java` → generated `.class`. Getting a *running*
  application to pick up new classes (class redefinition, application restart) is a separate job that
  Quarkus dev and DevTools already do well; it stays out. The pain being solved is the compile cost,
  and that is what this addresses. A consumer who wants live reload still runs their framework's dev
  mode; the difference is that the generated-code compile no longer rides that tool.
- **The generated closure, not consumer code.** graphitron compiles only what it emits, into
  `target/classes/<outputPackage>/`. The generated code's references *into* consumer services, jOOQ
  tables, and dependency jars are already compiled and on the resolved classpath (the classpath
  watcher depends on that being true). Recompiling the consumer's own hand-written code when a
  generated ABI changes remains the consumer build tool's job.

## What we already have

Four mechanics already exist; this item composes them rather than building from zero.

- **The changed-file set is already computed.** `JavaFile.writeToPath`
  (`graphitron-javapoet/src/main/java/no/sikt/graphitron/javapoet/JavaFile.java:150-171`) hashes the
  rendered content (`sha256()`) against the on-disk file and writes only on mismatch. It currently
  returns just the `Path`; a small change makes it report wrote-vs-skipped, yielding the changed set
  (delta) per run for free.
- **The dependency edges are a fact the pipeline already holds upstream.** Which fetcher references
  which type class, which conditions class, and so on, is known in the classified model long before
  rendering. The engine's edge set is a **projection of that model** (see *The sourcing seam*), not a
  walk over the rendered `TypeSpec`. A `TypeSpec`-`ClassName` walk *can* produce a superset of the
  edges, and is retained only as a **transitional fallback** with an explicit note that it is a
  stand-in, not the destination: sourcing structure from the emit artifact is generation-thinking's
  "recompute a derived value from output" smell, and it drags in noise (same-package and
  fully-qualified inline refs) precisely because it reverse-engineers structure the model already has.
- **The compile classpath and consumer bytecode are already resolved.** `DevMojo` declares
  `requiresDependencyResolution = COMPILE` and `AbstractRewriteMojo.buildCodegenLoader()` assembles
  the reactor classpath, consumer `target/classes`, and dependency jars: exactly a
  `StandardJavaFileManager`'s inputs.
- **The output sink and its watcher already exist.** The classpath watcher already watches
  `target/classes/…` for `.class` changes to rebuild the catalog; generated `.class` landing under
  `target/classes/<outputPackage>/` fits that model, and the `.java` orphan sweep
  (`GraphQLRewriteGenerator.java:264-284`, scoped to `OWNED_SUBPACKAGES`) has a `.class` twin whose
  scoping discipline is load-bearing (see *Output ownership*).

## The sourcing seam (the one-model guard)

The single interface `CompileDependencyGraph`, produced by one method, is the seam between "where the
edges come from" and "the compiler that consumes them". This is the load-bearing structural decision,
R333's "the projection seam re-sources from the facts" applied to a fourth consumer:

- **Today** the builder sources edges by coarsening the classified model (methods collapse into their
  emitting class; method-call seams collapse into file references) through an **exhaustive switch over
  the current classified-model leaves**, mirroring `CatalogBuilder.projectFieldClassification`. This is
  a projection over the same base the emitter reads, not a private walk.
- **The guard is compile-checked today, not a stub.** The switch above is live from slice 2: it is
  exhaustive over the classified-model source it projects, so any drift in that base (a new leaf or
  variant the switch does not cover) is a **compile error now**, not a dormant obligation that only
  bites once R333 lands. An inert obligation would be prose with a stub, which is precisely R333's
  revived-leaves fracture, the file graph persisting as a second private model of the dependency
  structure. If an *active* exhaustive switch over today's classified-model source genuinely cannot be
  written, that is a spec-blocking finding to surface, not a residual risk to wave through.
- **When R333's method graph exists** the builder re-sources by **re-targeting that same live switch**
  onto the method graph; the coarsening becomes a view over it. The switch stays exhaustive across the
  move, so the re-source cannot silently leave the file graph sourcing from the old base. This re-source
  is gated on R333's method graph existing, not on sibling R314 shipping: R314 (emit re-platforming) is
  the *parallel* emit consumer of the same model, a peer of this fourth consumer, not this consumer's
  trigger.

## Design: the incremental compile engine

- **A long-lived warm compiler.** One `JavaCompiler` (`ToolProvider.getSystemJavaCompiler()`, no new
  dependency on JDK 25) with a **reused `StandardJavaFileManager`**, so the classpath is scanned once
  at dev startup instead of per save. This is what defeats the fixed per-invocation cost; it is the
  same trick the frameworks use. Manager staleness across rounds is a named risk below.
- **A persistent file-level dependency graph** behind `CompileDependencyGraph`, nodes = generated
  compilation units, edges = "references type in", held in memory across saves alongside the LSP's
  in-memory state, updated (not rebuilt) as the delta lands.
- **The per-save recompile set.** Given the writer's delta, recompile =
  `delta ∪ {reverse-transitive dependents of the delta files whose ABI changed}`. Body-only changes do
  not propagate; ABI changes propagate along reverse edges. Compile that set into
  `target/classes/<outputPackage>`, sweep orphan `.class` (scoped, below), and route compiler
  diagnostics into the existing `WatchErrorFormatter` / LSP surface, the same tree validation errors
  already use.
- **Invalidation triggers.** A schema save produces the delta (regen path). A consumer `.class`
  change (the existing classpath watcher) invalidates the generated units that reference the changed
  consumer types, using the same graph, so a service ABI edit recompiles the generated fetchers that
  call it. A removed coordinate removes its `.java` (already), and must remove its `.class` and
  invalidate its dependents.

## Design forks (resolved)

- **Output ownership.** graphitron owns the generated package's bytecode: it compiles
  `<outputPackage>` into `target/classes/<outputPackage>/`, and consumers exclude `generated-sources`
  from their own build tool's compile so the two do not race as two writers into the same tree. A
  separate-output-dir alternative was rejected as it leaves the ownership split ambiguous. **The
  contract is verified, not just documented:** because `target/classes` is a *shared* tree (unlike
  `generated-sources/graphitron`, which graphitron solely owns), the `.class` orphan sweep is scoped to
  the same `OWNED_SUBPACKAGES` under `target/classes/<outputPackage>/`, so it can never delete consumer
  bytecode (a silent `NoClassDefFoundError`-at-runtime failure), and dev startup **fails fast** if the
  consumer's compile plugin is still configured to compile `generated-sources` (drafted in
  *First-client user-doc draft* below, landed by slice 6; it does not yet exist).
- **Sequencing vs R333/R314.** Ship the model-sourced graph now behind `CompileDependencyGraph` (see
  *The sourcing seam*), rather than blocking on either. The compile-dependency graph is a coarsening of
  R333's method graph and becomes a view over it once that method graph exists: a fourth consumer
  alongside code-gen, the LSP, and the MCP server, matching R333's "one base, many consumers, no
  consumer owns a private model" thesis. R314 (emit re-platforming) is a peer consumer of the same
  model, not a gate on this one. No hard `depends-on`; the migration is guarded by the live
  compile-checked exhaustive switch, not deferred to good intentions.

## First-client user-doc draft

Per workflow.adoc's item conventions, the user-visible contract is drafted here as the first-client
check: if it does not read simply, the design is wrong. This is the block slice 6 lands in
`getting-started.adoc`'s dev-loop section (final wording may tighten, the shape is the contract).

> **`graphitron:dev` compiles the generated code for you.** In a dev session the goal writes the
> generated Java *and* compiles it, dropping the `.class` files straight into
> `target/classes/<outputPackage>/` as you edit the schema. Only the classes affected by an edit are
> recompiled, so a one-field change is fast even on a large schema. You no longer need `quarkus:dev`
> or the IDE to recompile the generated code; they still own reloading it into a running app.
>
> **One-time setup: let graphitron own the generated package.** Because graphitron now compiles the
> generated sources itself, your own build must not also compile them, or the two race to write the
> same `.class` files. Exclude the generated-sources root from your module's compile (it stays a
> source root for IDE indexing and go-to-definition; it is just not double-compiled):
>
> ```xml
> <plugin>
>   <groupId>org.apache.maven.plugins</groupId>
>   <artifactId>maven-compiler-plugin</artifactId>
>   <configuration>
>     <excludes>
>       <exclude>**/<outputPackage-as-path>/**</exclude>
>     </excludes>
>   </configuration>
> </plugin>
> ```
>
> If the exclusion is missing, `graphitron:dev` stops at startup rather than racing silently:
>
> ```
> graphitron:dev: your build compiles the generated package <outputPackage> itself, which would
> race graphitron's own compilation into target/classes. Exclude the generated-sources root from
> maven-compiler-plugin (see the dev-loop docs), or run with -Dgraphitron.dev.compile=false to let
> your build own it.
> ```

The `-Dgraphitron.dev.compile=false` escape hatch (fall back to today's generate-only behaviour, let
the consumer's tool compile) is part of slice 6's surface, so a consumer who cannot change their build
config is not blocked.

## Risk centers (where the spec's difficulty concentrates)

- **ABI-vs-body discrimination is the whole game.** Treating every content change as ABI-affecting is
  trivially correct but collapses pruning (a body edit to a hot base type would recompile thousands).
  The engine hashes each unit's **public signature surface** (supertypes, implemented interfaces,
  method signatures, field types, and `public static final` constant *values*, because of compile-time
  inlining) separately from its body, and propagates along reverse edges only when the ABI hash moves.
  The constant-inlining and annotation-retention cases are the subtle correctness work. **This hash is
  derived state, not a parallel type system:** the signature surface is a re-encoding of type facts the
  model already classifies (supertypes, signatures, field types), so per "model metadata over parallel
  type systems" it is a candidate to derive from the model's type facts once R333/R314 land, the same
  re-source-from-facts move as the sourcing seam, rather than hashing rendered signatures forever.
- **This is R333's reserved memoized-query architecture, arriving on the compile side first.** The
  persistent in-memory graph + ABI-hash cache + warm file manager is precisely the incremental,
  demand-driven, memoized-recompute architecture R333 reserves and declares out of scope. Building it
  here first is deliberate and defensible: the compile step is the least model-entangled place to put
  it, so it can land before R333 without pre-empting the model's own memoization design. This is stated
  so it is a conscious choice, not an accidental second engine.
- **Warm file-manager staleness.** Reusing a file manager across rounds risks serving a cached symbol
  for a class we just recompiled: a silent-wrong-output bug, not a crash. The plan is a fresh
  `JavacTask` per round over the reused manager, with explicit invalidation of recompiled and removed
  outputs, and a test asserting a stale symbol never survives a round.
- **Graph completeness has no live guard (accepted residual risk).** The graph must be a *superset* of
  javac's true dependencies or an incremental compile can silently skip a file that needed
  recompiling. The offline invariant harness (below) falsifies incompleteness over a chosen edit
  sequence, but there is no runtime guard for the *actual* live-loop edit sequence. Candidate live
  guards: have javac emit its own per-round dependency info and assert the graph was a superset of it,
  or periodically diff a scoped full compile against the incremental `.class` set. If a live guard is
  not built, its absence is an **explicitly accepted** residual risk backed only by the offline
  harness, not an unstated gap.

## The load-bearing correctness invariant (two clauses)

Mirroring `IdempotentWriterTest`'s multi-clause contract (and R333 thread I's closure), the acceptance
gate is a **pair**, because a degenerate engine that recompiles everything every save passes a
correctness-only gate perfectly while delivering none of the item's value:

- **(a) Correctness / completeness.** After a sequence of schema edits through the incremental engine,
  the `target/classes/<outputPackage>` tree is byte-for-byte identical to a clean full compile of the
  final sources. Falsifies graph incompleteness, ABI-hash misses, and stale-symbol leaks.
- **(b) Pruning / ABI discrimination.** For a known edit sequence, an assertion over the *recompile
  set* that a body-only edit excluded its reverse-dependents (and that an ABI edit included exactly the
  transitive dependents). This is what makes "the design is wrong if it can't pass cheaply" bite;
  without it, "cheaply" is unpinned.

## Slices and test tiers

1. **Writer reports its delta.** `JavaFile.writeToPath` (and the generator's `write` loop,
   `GraphQLRewriteGenerator.java:207-262`) surface the wrote-vs-skipped set. `@UnitTier` on the writer;
   no behavior change to emitted content, so existing determinism tests stay green.
2. **Model-sourced dependency graph behind `CompileDependencyGraph`.** Build the file-level graph as a
   coarsening projection of the classified model through an **active exhaustive switch over the current
   classified-model leaves** (mirroring `CatalogBuilder.projectFieldClassification`), so drift in the
   base the builder projects over is a compile error today; the `TypeSpec`-`ClassName` walk is retained
   only as a labelled transitional fallback. `@UnitTier` on the graph builder; `@PipelineTier` asserting
   a realistic SDL yields the expected edges (e.g. a fetcher unit references its type unit and its
   conditions unit). The switch is live from this slice, not a stub; the R333 step (a later item) only
   re-targets it onto the method graph.
3. **ABI hashing + recompile-set algorithm.** Signature-surface hash and the
   `delta ∪ ABI-affected-reverse-dependents` computation as pure functions over the graph. `@UnitTier`
   with hand-built graphs covering body-only (no propagation), ABI change (one-hop and transitive), and
   constant-value change (propagates).
4. **The warm compiler + incremental engine.** Wire the `JavaCompiler` / reused
   `StandardJavaFileManager`, compile the recompile set into `target/classes`, scoped `.class` orphan
   sweep, diagnostics into `WatchErrorFormatter`. `@UnitTier` on the engine over a synthetic source
   set; the stale-symbol regression test lives here.
5. **The correctness invariant harness (two clauses).** Clause (a) incremental-equals-clean-full and
   clause (b) recompile-set pruning, as above. New tier-crossing harness in the maven-plugin module;
   the acceptance gate.
6. **Dev-loop integration.** Fold the engine into `DevMojo`: schema-save and classpath-change triggers
   drive it, consumer `.class` changes invalidate dependent generated units, Ctrl+C shuts the engine
   down cleanly, dev startup fails fast on a mis-configured consumer compile (with the
   `-Dgraphitron.dev.compile=false` opt-out for consumers who cannot change their build config). Extend
   `DevMojoTest`, including a clause mirroring `IdempotentWriterTest`'s third (a planted `.class`
   outside owned sub-packages survives a sweep) and the fail-fast / opt-out paths. Land the
   *First-client user-doc draft* block into `getting-started.adoc`'s dev-loop section.

## Non-goals

- Application hot-reload / class redefinition (frameworks own this).
- Compiling consumer hand-written code (their build tool owns this).
- A configurable compiler backend; ECJ (`org.eclipse.jdt`) is a *fallback* to revisit only if the
  JDK compiler's incremental behavior or error-recovery proves inadequate, not part of this item.

## Relationship to other items

- **R333 (The Graphitron data model), R314 (emit re-platforming):** the dependency graph is a
  coarsening of R333's method graph; slice 2's builder sources from the classified model behind
  `CompileDependencyGraph` through a live exhaustive switch, which is later re-targeted onto the R333
  method graph once that graph exists. R314 is a peer consumer of the same model, not a gate on this
  re-source. Sequenced as ship-now-behind-a-guarded-seam, not blocked.
- **R349 (source-watcher decoupling), R341 (MCP transport seam):** this engine is a fourth long-lived
  component in the one-JVM dev loop those items shaped; it follows their lifecycle and shutdown
  conventions.
