---
id: R410
title: "graphitron:dev owns incremental compilation of generated sources"
status: In Review
bucket: feature
priority: 3
theme: lsp
depends-on: []
created: 2026-07-01
last-updated: 2026-07-03
---

# graphitron:dev owns incremental compilation of generated sources

## In one paragraph

`mvn graphitron:dev` today generates Java sources and stops: it writes `.java` under
`target/generated-sources/graphitron` through the idempotent writer and relies on *something else*,
the Quarkus dev environment, Spring Boot DevTools, or IntelliJ's incremental compiler, to turn those
sources into `.class`. The primary reason to own that step is not build speed but a capability it
unblocks: the dev loop's **MCP server should execute GraphQL queries against the generated resolvers
in-process**, so an agent (or the developer) can ask "what does this query return" and get a real
answer without spinning up Quarkus or any app server. That needs the generated code present as loaded,
runnable `.class` on the dev JVM, which today only an external build produces; compiling in-process is
the prerequisite. (The query-execution capability itself is a separate item; this one delivers the
loadable classes it stands on.) Doing the compile *incrementally* is what keeps it viable at scale: on
a real subgraph of thousands of generated files a full compile is minutes, and because we author the
generated code we know its reference graph exactly, so a one-field schema edit recompiles only the
affected sub-closure. That graph is a file-level coarsening of the same structure R333 models as the
back half of the lowering (a referentially-closed graph of emitted Java methods); the sourcing seam
re-sources from R333's method graph once that graph exists, and the coverage obligation that keeps it
one model (not a revived parallel structure) is a compile-checked exhaustive switch that is live from
day one, not a stub promised in prose. A *secondary* benefit, gated on a per-framework spike (see
*Output ownership*), is that a co-running framework dev process can consume the same fresh classes
instead of recompiling them.

## Scope boundary

Two boundaries keep this item finite and keep it from re-implementing a framework.

- **Compile and load into the dev JVM; not consumer-app hot-reload.** This owns generated `.java` →
  generated `.class` and makes those classes loadable in the `graphitron:dev` JVM so the MCP server can
  execute against them (the driver above). What stays out is swapping new classes into the *consumer's*
  separately-running application: class redefinition and app restart are jobs Quarkus dev and Spring
  DevTools already do, and a consumer who wants their app live-reloaded still runs their framework's dev
  mode. Whether that framework can additionally *consume* graphitron's compiled classes, skipping its
  own recompile of the generated code, is the secondary benefit under spike, not a promise this item
  makes.
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
- **Startup generates *and* compiles the whole tree.** The first dev-loop pass emits every generated
  source and compiles the complete set into `target/graphitron-classes`, so the exclusive dir holds a
  full, runnable image before any edit. This is required, not an optimisation: the MCP server executes
  against a whole graph, and a half-populated dir is not runnable; it also closes the classpath gap in
  *Output ownership*, since a consumer who takes the advisory `generated-sources` exclusion has no
  generated `.class` in `target/classes`, so graphitron-classes must be complete from startup or an
  un-edited class would be missing. Every later save is incremental over that baseline.
- **The per-save recompile set.** Given the writer's delta, recompile =
  `delta ∪ {reverse-transitive dependents of the delta files whose ABI changed}`. Body-only changes do
  not propagate; ABI changes propagate along reverse edges. Compile that set into
  `target/graphitron-classes/<outputPackage>`, sweep orphan `.class` (below), and surface any compiler
  diagnostics per *Surfacing compile diagnostics* below.
- **Invalidation triggers.** A schema save produces the delta (regen path). A consumer `.class`
  change (the existing classpath watcher) invalidates the generated units that reference the changed
  consumer types, using the same graph, so a service ABI edit recompiles the generated fetchers that
  call it. A removed coordinate removes its `.java` (already), and must remove its `.class` and
  invalidate its dependents.

## Surfacing compile diagnostics

In steady state this is close to dead code: a schema problem that would break the generated code should
be rejected at validate time as a schema-anchored `ValidationError` (validator-mirrors-classifier), long
before javac runs. But a compile diagnostic that reaches this engine has *three* possible sources, and
only two are defects, so the messaging must not treat every one as an alarm:

- **A missing validator check**, a generator-side invariant that no validate-time arm guards. The real
  fix is to add the validator arm (per "no generator-side invariant goes unchecked at validate time");
  the compile error is the symptom.
- **A graphitron bug**: we emitted invalid Java. Also a defect.
- **Transient consumer inconsistency, and this one is benign and self-resolving.** The classpath watcher
  fires on consumer `.class` changes, so a consumer mid-refactor, a service whose ABI just changed while
  the generated fetcher that calls it has not yet been regenerated, or a consumer class that is
  momentarily uncompilable, can fail a round through no fault of the schema or the generator. The next
  consumer compile or the next regen clears it. This is the *most common* live cause, so it sets the
  default tone.

The engine treats compile errors as a safety net and never swallows one, but it does not cry wolf: the
console block reports the failure and names the offending generated file without asserting a defect,
because mid-edit inconsistency, not a bug, is the usual cause. A compile error that *survives a clean
regen and a clean consumer compile* is the signal that it is really one of the first two.

- **No schema re-anchoring (explicit non-goal).** Tracing a javac error back to the emitting schema
  coordinate is a nice-to-have, not a requirement of this item. Compile diagnostics stay anchored to the
  generated `.java` where javac reports them (file path + `line:col` + severity + message).
- **A small dedicated collection, not the schema report.** Diagnostics are collected per compile round
  into their own list, kept separate from `ValidationReport` (forcing a generated-file error into a
  schema-coordinate `ValidationError` would fabricate a coordinate it does not have). They surface
  through the *same three consumers* validation errors already reach:
  - **Console dev loop** renders them as a distinct compile-error block (a companion to
    `WatchErrorFormatter`'s validation tree), labelled as generated-code compilation failure so the user
    is not sent hunting in the schema for a javac error. This is the primary human channel.
  - **MCP `diagnostics` tool** includes them with a `source: "compile"` discriminator alongside the
    existing schema entries, so an agent editing through MCP reads compile failures back in the tool it
    already polls.
  - **LSP** publishes them against the generated file's URI, best-effort, so an editor with that file
    open shows them.
- **A failed round does not report success.** javac emits no `.class` for a unit with errors, so a
  failing unit keeps its last-good `.class`; the round surfaces the failure rather than reporting a clean
  compile, so the user knows the running tree is stale for those units instead of silently trusting it.

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
    copy is harmless. No misconfiguration corrupts bytecode on disk; the exclusion of `generated-sources`
    from the consumer compile becomes an *advisory* optimisation (avoid redundant work), not a
    load-bearing guard. One caveat, surfaced by the Quarkus spike in the open-question bullet below:
    front-loading the dir helps only frameworks that consume external `.class`, so the "put it first on
    the classpath" step is framework-scoped, for `quarkus:dev` it is a no-op-to-hazard and is omitted.
  - **Sweep safety is now structural.** Because `target/graphitron-classes` is graphitron-exclusive
    (unlike the shared `target/classes`), the `.class` orphan sweep can drop anything under it not
    emitted this run without needing the `OWNED_SUBPACKAGES` scoping as a safety fence, that scoping was
    the shared-tree guard against deleting consumer bytecode, and it no longer has any consumer bytecode
    to protect. The sweep still mirrors the `.java` sweep for parity; its *safety* just no longer
    depends on scoping.
  - **Resolved by spike: no co-run reload benefit for Quarkus; the Quarkus consumer's value is the
    MCP-execution driver, not framework reload.** A source-level spike against
    `io.quarkus.deployment.dev.RuntimeUpdatesProcessor` (Quarkus 3.34.5, the sakila-example's pinned
    version) found `quarkus:dev` watches its *own* source roots and its *own* compile output only: on a
    change it recompiles the sources itself through its `CompilationProvider` into the module's
    `classesPath`, and it never scans an arbitrary external classes dir like `target/graphitron-classes`.
    Because the `generate` mojo registers the generated dir as a compile source root
    (`GenerateMojo.java:30`, `addCompileSourceRoot`), Quarkus keeps recompiling the generated sources
    itself; there is no dev-mode switch to say "use these precompiled classes for this source root". So
    front-loading our dir on a `quarkus:dev` classpath is a no-op at best and, if our copy ever lags
    Quarkus's own recompile, *shadows* the fresher classes (live reload silently does nothing). The
    conclusion: for a Quarkus consumer, do **not** front-load `target/graphitron-classes`; graphitron's
    compiled output serves the in-process MCP execution driver, while Quarkus goes on compiling the
    generated sources for its own app exactly as today. The co-run consume benefit survives only for
    frameworks that watch the classpath and restart on external `.class` (Spring Boot DevTools) and for
    plain `java` / IDE runs. Slice 6 confirms the Quarkus negative empirically before the docs claim
    anything.
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

> **`graphitron:dev` compiles the generated code, so the dev tools can run it.** In a dev session the
> goal writes the generated Java *and* compiles it into its own output directory,
> `target/graphitron-classes/<outputPackage>/`, keeping a runnable image of your whole schema as you
> edit. Only the classes an edit touches are recompiled, so a one-field change is fast even on a large
> schema. The immediate payoff: the dev loop's MCP tools can *execute a query against your resolvers
> in-process*, with no app server and no `quarkus:dev` needed to see what a query returns.
>
> **Do you need to configure anything? Usually not.** graphitron writes to its own directory and never
> touches a file your build owns, so there is nothing to guard against and no required setup. Two
> situations where a one-liner helps:
>
> * **Spring Boot DevTools, plain `java`, IDE runs.** These load `.class` off the classpath, so putting
>   `target/graphitron-classes` **ahead of** `target/classes` on the run classpath lets your running app
>   use graphitron's fresh classes directly. Ordering is what matters; graphitron's copy wins over any
>   copy your own build produced.
> * **Quarkus dev (`quarkus:dev`).** Do *not* add graphitron's directory to the Quarkus classpath.
>   Quarkus recompiles the generated sources itself and will not pick up an external `.class`, so
>   front-loading our directory buys nothing and only risks shadowing Quarkus's own reload. Run
>   `quarkus:dev` exactly as today; graphitron's compile still powers the in-process MCP query tools
>   running alongside it.
>
> **Optional: skip the redundant compile.** Independently of the above, you can stop your own build from
> re-compiling the generated sources (they stay a source root for IDE indexing and go-to-definition
> either way). This only saves double-work; it is never required for correctness, because graphitron
> writes to a separate directory:
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
> **If you skip the classpath step, nothing breaks.** Your app runs exactly as today: your build
> compiles the generated sources into `target/classes` and runs from there, while graphitron's separate
> output still powers the in-process MCP query tools, it just is not fed to your running app. There is
> no race and no corruption to guard against, because the two never write the same file. To turn
> graphitron's compile off entirely (giving up the MCP query tools too), run with
> `-Dgraphitron.dev.compile=false`.

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
   `target/graphitron-classes`, `.class` orphan sweep of that dir, and collect the compile-diagnostic
   list per *Surfacing compile diagnostics*. `@UnitTier` on the engine over a synthetic source set,
   including a source that fails to compile (asserts the diagnostic is collected, the failing unit keeps
   its last-good `.class`, and the round reports failure not success); the stale-symbol regression test
   lives here.
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
   dir; the `-Dgraphitron.dev.compile=false` opt-out path; a precedence assertion that a stale copy
   of a class in `target/classes` is shadowed by graphitron's fresh copy when its dir is first; and that
   a compile error reaches the console block and the MCP `diagnostics` tool (with `source: "compile"`).
   Confirm the per-framework reload story the spike established (`quarkus:dev` does not consume an
   external `.class` and is documented as unsupported for co-run reload, with the Quarkus value routed
   to MCP execution; Spring DevTools consumes it) with a live check before the docs claim it, and land
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
