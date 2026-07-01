---
id: R410
title: "graphitron:dev owns incremental compilation of generated sources"
status: Spec
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
- **The generated closure, not consumer code.** graphitron compiles only what it emits, into a
  graphitron-exclusive output dir (`target/graphitron-classes/<outputPackage>/`, see *Output
  ownership*), never the shared `target/classes`. The generated code's references *into* consumer
  services, jOOQ tables, and dependency jars are already compiled and on the resolved classpath (the
  classpath watcher depends on that being true). Recompiling the consumer's own hand-written code when
  a generated ABI changes remains the consumer build tool's job.

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
- **The consumer-`.class` watcher and the orphan-sweep pattern already exist.** The classpath watcher
  already watches `target/classes/…` for *consumer* `.class` changes to rebuild the catalog; that watch
  is unchanged and continues to drive the consumer-ABI invalidation trigger below. graphitron's own
  generated `.class` lands in the separate `target/graphitron-classes/<outputPackage>/` (see *Output
  ownership*), so it does not feed that watcher and does not need to. The `.java` orphan sweep
  (`GraphQLRewriteGenerator.java:264-284`, scoped to `OWNED_SUBPACKAGES`) is the template for the
  `.class` twin, which sweeps the graphitron-exclusive dir.

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
  `target/graphitron-classes/<outputPackage>`, sweep orphan `.class` (below), and route compiler
  diagnostics into the existing `WatchErrorFormatter` / LSP surface, the same tree validation errors
  already use.
- **Invalidation triggers.** A schema save produces the delta (regen path). A consumer `.class`
  change (the existing classpath watcher) invalidates the generated units that reference the changed
  consumer types, using the same graph, so a service ABI edit recompiles the generated fetchers that
  call it. A removed coordinate removes its `.java` (already), and must remove its `.class` and
  invalidate its dependents.

## Design forks (resolved)

- **Output ownership: a graphitron-exclusive output dir, not the shared `target/classes`.**
  graphitron compiles `<outputPackage>` into `target/graphitron-classes/<outputPackage>/`, a directory
  graphitron **solely writes**, and that dir is placed **first** on the dev/runtime classpath, ahead of
  `target/classes`. This is the load-bearing decision, and it is chosen precisely to make incremental
  compilation *sound by construction*: incremental compile is only correct when a single authority owns
  the output set (a second compiler that does not know graphitron's dependency graph recompiles a
  different set, so an interleaved run leaves `target/classes` holding a mix of ABIs from two fronts,
  a `LinkageError`/`NoSuchMethodError` at reload time). An exclusive dir gives graphitron that sole
  authority without asking any other tool to stand down.
  - **Why not the shared tree.** Writing generated `.class` into `target/classes/<outputPackage>/` (the
    earlier draft) races every other compiler pointed at the generated source root. That source root is
    a *conventional* one (added so the IDE indexes it), so three actors compile it into `target/classes`
    by default: `maven-compiler-plugin` (during a `compile`/`package`/framework goal), the IDE
    (continuously, governed by its own module settings, **not** by `maven-compiler-plugin` `<excludes>`),
    and the framework dev process (Quarkus/Spring, which the scope boundary *intends* to run alongside).
    A fail-fast that inspects the pom catches only the first and is blind to the IDE and the framework
    JVM, exactly the two most likely to be running next to `graphitron:dev`. The shared tree made the
    consumer-side exclusion a *precondition of correctness* that graphitron could not enforce.
  - **Misconfiguration degrades safely, never corrupts.** Because the dir is exclusive, no file-write
    race is possible regardless of what the consumer's other tools do. If the consumer forgets the
    one-time classpath setup, their framework simply falls back to compiling `generated-sources` into
    `target/classes` itself, today's slower behaviour, and graphitron's output sits unread; degraded,
    not broken. If they add the dir first but *also* let their tool compile `generated-sources`, the
    graphitron-classes-first ordering means the fresh copy always wins and the redundant `target/classes`
    copy is harmless. Every misconfiguration is safe; the exclusion of `generated-sources` from the
    consumer compile becomes an *advisory* optimisation (avoid redundant work), not a load-bearing guard.
  - **Sweep safety is now structural.** Because `target/graphitron-classes` is graphitron-exclusive
    (unlike the shared `target/classes`), the `.class` orphan sweep can drop anything under it not
    emitted this run without needing the `OWNED_SUBPACKAGES` scoping as a safety fence, that scoping was
    the shared-tree guard against deleting consumer bytecode, and it no longer has any consumer bytecode
    to protect. The sweep still mirrors the `.java` sweep for parity; its *safety* just no longer
    depends on scoping.
  - **Open question, bounds where the feature helps (slice 6 answers it).** graphitron compiles; the
    framework reloads. That only works for frameworks that reload from an externally-produced `.class`
    on the classpath. Spring Boot DevTools watches the classpath and restarts on `.class` changes, so it
    does. Quarkus dev is more source-oriented and may reload only what *it* recompiled; if so, the
    feature buys a Quarkus consumer nothing and slice 6 must say so plainly rather than imply universal
    benefit. Slice 6 pins the per-framework reload behaviour before the docs claim it.
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
> generated Java *and* compiles it, dropping the `.class` files into its own output directory,
> `target/graphitron-classes/<outputPackage>/`, as you edit the schema. Only the classes affected by an
> edit are recompiled, so a one-field change is fast even on a large schema. You no longer need
> `quarkus:dev` or the IDE to recompile the generated code; they still own reloading it into a running
> app.
>
> **One-time setup: put graphitron's output first on your run classpath.** graphitron writes its
> compiled classes to `target/graphitron-classes` so nothing ever writes to the same file it does.
> For your running app (dev mode, tests) to pick up the fast, freshly-compiled classes, add that
> directory to the runtime classpath **ahead of** `target/classes`. In Maven, add it as an extra
> classpath element for your framework's dev/run goal (exact key depends on the plugin); the ordering
> is what matters, graphitron's copy must win over any copy your own build produces.
>
> You can *optionally* also stop your own build from re-compiling the generated sources (it stays a
> source root for IDE indexing and go-to-definition either way). This only saves the redundant compile;
> it is not required for correctness, because graphitron writes to a separate directory and the
> classpath ordering already decides which copy wins:
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
> **If you skip the setup, nothing breaks, it just isn't faster.** Without the classpath entry your
> app runs exactly as today: your build compiles the generated sources into `target/classes` and runs
> from there, and graphitron's separate output goes unused. There is no race and no corruption to guard
> against, because the two never write the same file. To turn graphitron's compile off entirely (so it
> does not spend time producing output you are not consuming), run with `-Dgraphitron.dev.compile=false`.

The `-Dgraphitron.dev.compile=false` switch (fall back to today's generate-only behaviour) is part of
slice 6's surface. Note what the exclusive-dir design buys over a shared `target/classes`: there is no
fail-fast startup check to write, because there is no cross-writer race to fail on; every
misconfiguration degrades to today's behaviour instead of corrupting bytecode.

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
  the `target/graphitron-classes/<outputPackage>` tree is byte-for-byte identical to a clean full
  compile of the final sources. Falsifies graph incompleteness, ABI-hash misses, and stale-symbol leaks.
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
   `StandardJavaFileManager`, compile the recompile set into the graphitron-exclusive
   `target/graphitron-classes`, `.class` orphan sweep of that dir, diagnostics into
   `WatchErrorFormatter`. `@UnitTier` on the engine over a synthetic source set; the stale-symbol
   regression test lives here.
5. **The correctness invariant harness (two clauses).** Clause (a) incremental-equals-clean-full and
   clause (b) recompile-set pruning, as above. New tier-crossing harness in the maven-plugin module;
   the acceptance gate.
6. **Dev-loop integration.** Fold the engine into `DevMojo`: schema-save and classpath-change triggers
   drive it, generated `.class` lands in `target/graphitron-classes` (surfaced as a classpath element
   ahead of `target/classes`), consumer `.class` changes invalidate dependent generated units, Ctrl+C
   shuts the engine down cleanly, and `-Dgraphitron.dev.compile=false` disables graphitron's compile so
   it does not produce unread output. No fail-fast: the exclusive dir means a mis-set-up consumer
   degrades to today's behaviour rather than corrupting, so there is nothing to fail on. Extend
   `DevMojoTest`: the sweep drops an orphaned generated `.class` (removed coordinate) from the exclusive
   dir; the `-Dgraphitron.dev.compile=false` opt-out path; and a precedence assertion that a stale copy
   of a class in `target/classes` is shadowed by graphitron's fresh copy when its dir is first. Pin the
   per-framework reload behaviour (Spring DevTools reloads from external `.class`; Quarkus dev's
   source-oriented reload verified or documented as unsupported) so the docs claim only what holds. Land
   the *First-client user-doc draft* block into `getting-started.adoc`'s dev-loop section.

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
