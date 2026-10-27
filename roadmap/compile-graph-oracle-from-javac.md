---
id: R1103
title: "Derive the compile-graph oracle from javac and retire referencedClassNames"
status: Ready
bucket: architecture
priority: 8
theme: testing
depends-on: []
created: 2026-10-26
last-updated: 2026-10-27
---

# Derive the compile-graph oracle from javac and retire referencedClassNames

## Goal

The incremental compiler's dependency graph is checked against the dependencies javac actually resolves when it compiles the generated sources, instead of against a reconstruction from the JavaPoet `TypeSpec` tree. The check lives in test sources, and `TypeSpec.referencedClassNames()` and `TypeSpecReferenceWalk` are deleted. Nothing changes for consumers. For graphitron, the check loses its blind spots (the first run against today's tree already finds one real gap in the graph), and the `graphitron-javapoet` fork loses the addition that blocks replacing it with Palantir's JavaPoet (R174, which depends on this item).

Terms used below: the *projected graph* is the `CompileDependencyGraph` that `PlanCompileGraph` derives from the emit plan, and the one `IncrementalCompiler` reads to decide which generated files a change forces to recompile. The *oracle* is the independent account of what each generated unit really references, which `IncrementalCompileHarnessTest.threeLegOracle_projectedGraphMatchesTheEmitArtifact` compares the projected graph against. A *unit* is one generated top-level class, named by its FQCN.

## Current behaviour

Verified against trunk at `2031cfc68`:

* The oracle is `TypeSpecReferenceWalk.edges(emittedUnits)` (`graphitron/src/main`, package `no.sikt.graphitron.rewrite.compile`). Its only caller is the harness test. It unions two sources: `TypeSpec.referencedClassNames()`, a fork addition in `graphitron-javapoet` that walks every `$T` argument through nested `$L` code blocks, anonymous classes, annotations and type-variable bounds (through `CodeBlock.args()`, package-private there and in Palantir's JavaPoet alike), and a regex scan of the rendered source for generated FQCNs baked into strings. Its javadoc records a residual neither source sees: a same-package simple name baked into a code-bearing string.
* The harness test checks three legs over one realistic schema (52 generated units): (1) the projected node set equals the emitted unit set; (2) every reference the oracle finds is a projected edge, with frozen-scaffold units (`PlanCompileGraph.FROZEN_SCAFFOLD_KINDS`) exempt as sources; (3) every projected edge the oracle does not find sits inside `PlanCompileGraph`'s declared superset (`Projection.declaredSuperset()`). The legs are inline assertions, so no test can feed them a deliberately broken graph.
* `CompileDependencyGraph` is a three-method interface (`nodes`, `directReferences`, `directDependents`), so a test can wrap a projected graph to drop or add one edge.
* Both existing in-process compiles call `JavaCompiler.CompilationTask.call()` on a plain task: `IncrementalCompileEngine` (main code, the dev loop's compiler) and `EmittedCodeHarness` (test code, compiles in order to load and invoke). `GenerationResult.emitted()` holds the paths the run wrote, the files `IncrementalCompiler` compiles in the dev loop.
* `GraphitronContextInterfaceGenerator` names the carrier as `ClassName.bestGuess("TenantConnections")`, a package-less name that renders as the same-package simple name. The walk sees that `ClassName`, but its canonical name `TenantConnections` matches no generated FQCN, so the reference is dropped: a blind spot outside the documented residual. `PlanCompileGraph.addFixedKindEdges` gives `GRAPHITRON_CONTEXT` no outbound edge, and `GRAPHITRON_CONTEXT` is not in `FROZEN_SCAFFOLD_KINDS`, so leg 2 does check it as a source.

## Verified premise

A throwaway prototype, run against the harness schema at `2031cfc68`, compiled the 52 generated units through a `JavacTask` with a `TaskListener` on `ANALYZE` events, collected references from the analysed trees as planned below, and compared:

* Everything the walk finds, javac finds: 83 walk edges, 86 javac edges, none in the walk alone. The regex scan contributes nothing javac lacks.
* javac finds three edges the walk misses: `GraphitronContext` to `TenantConnections` (the `bestGuess` case above), `GraphitronConnectionInstrumentation` to `GraphitronTransactionProvider` (already a projected edge), and `ConnectionResult` to `ConnectionHelper` (a frozen-scaffold source, exempt).
* With javac as the oracle, leg 2 reports exactly one gap, `GraphitronContext` to `TenantConnections`, and leg 3 reports none.
* On this corpus, expression types add nothing beyond resolved elements; the unit tests below pin the case where they do.
* The whole prototype test, generation included, ran in 15 s; the compile adds a few seconds to the existing pipeline-tier test.

The prototype was deleted; it is not part of the tree.

## Plan

### The javac reference index

* **`JavacReferenceIndex`**, a new test-source class beside the harness (`graphitron/src/test/java/no/sikt/graphitron/rewrite/compile/`), with one entry point: given source files, a compile classpath and the set of generated unit FQCNs, it returns, for each unit, the set of other units it references.
* **One dedicated compile.** It casts `ToolProvider.getSystemJavaCompiler().getTask(...)` (options `-proc:none`, as `IncrementalCompileEngine` passes; class output into a temporary directory) to `com.sun.source.util.JavacTask` and registers a `TaskListener`. On each finished `TaskEvent.Kind.ANALYZE` event it scans the event's compilation unit with a `TreePathScanner`. A compile that fails fails the caller with javac's diagnostics. Only the exported `com.sun.source.*` and `javax.lang.model.*` APIs are used, so no `--add-exports` enters the surefire configuration.
* **What counts as a reference.** For every tree node: the element `Trees.getElement(path)` resolves, and the type `Trees.getTypeMirror(path)` gives, decomposed through type arguments, array components, wildcard and type-variable bounds, intersections and unions. Each is mapped to its outermost enclosing `TypeElement`, so a nested type counts as its top-level unit. A reference is kept when that unit is generated and is not the unit being scanned. Types matter beside elements because some dependencies have no identifier: in `foo(Gen.make())`, the overload of `foo` javac picks depends on the type `make()` returns and on its supertypes, and no name in the source spells that type.
* **Why the trees and not the class files.** Analysed trees keep every reference resolved: same-package simple names, names inside code a generator baked as a string (it is code by the time javac reads it), and compile-time constants. A class-file scan such as jdeps misses the last case, because javac inlines a constant's value at the use site.
* **The input is the files the run wrote.** The unit set is `emittedUnits.keySet()`, which leg 1 already equates with the projected nodes. Each unit's source file is resolved from its FQCN under the run's generated-sources root (`<root>/<fqcn with dots as slashes>.java`), and the index fails if one is missing. Resolving from the unit set, rather than reading `GenerationResult.emitted()`, avoids filtering that set's non-Java paths (it also records the SDL resource) and ties the compiled files to exactly the units the legs reason about. These are the files the dev loop compiles, not a second rendering of the `TypeSpec`s. Clause A of the harness keeps rendering `TypeSpec`s in memory through its `sources(...)` helper: it compares an incremental compile with a full one, both rendered the same way, so its input choice does not affect what it proves, and changing it is outside this item.
* **Why a dedicated compile.** `IncrementalCompileEngine` is main code; a listener seam there would exist only for a test. `EmittedCodeHarness` compiles in order to load classes, and the oracle loads nothing. One extra compile in one pipeline-tier test costs a few seconds.

### The legs, factored and fed by javac

* **`CompileGraphOracle`**, a test-source class beside the harness, holds legs 2 and 3 as functions returning what they find rather than asserting: `gaps(graph, references, frozenSources)` (oracle references the graph lacks, frozen units exempt as sources) and `undeclared(graph, declaredSuperset, references)` (projected edges the oracle does not find and the declared superset does not cover). Leg 1 stays an inline assertion; it reads only the graph and the emitted unit set.
* `threeLegOracle_projectedGraphMatchesTheEmitArtifact` builds the index from the run's files and asserts both functions return empty. The bounded-gap leg stays: it is the over-collection check, and it needs an oracle no less than it did before. Its declared superset is unchanged; the prototype shows no undeclared surplus under javac.

### The one graph fix the new oracle requires

* `PlanCompileGraph.addFixedKindEdges` moves `GRAPHITRON_CONTEXT` out of the no-edge group into its own arm with a **precise** edge to the `TenantConnections` unit. The class javadoc defines precise edges as references the emitted source actually makes, and this one is unconditional: `GraphitronContextInterfaceGenerator` always names the carrier, and `EmitPlan` always plans `TenantConnections`. Schema-invariance of the target is not a reason to declare: `FACADE` to `GraphitronRuntime` and `QUERY_NODE_FETCHER` to `GraphitronContext` are already precise edges into schema-invariant units. The connection runtime's internal wiring is declared because parts of it are configuration-conditional, which this reference is not. Precise also keeps the edge checked: leg 3 fails if the generator ever stops making the reference, which a declared edge would hide. `GraphitronContextInterfaceGenerator`'s `bestGuess` name renders correctly and stays; it is no longer a blind spot once javac is the oracle.

### Deletions

* `graphitron/src/main/java/no/sikt/graphitron/rewrite/compile/TypeSpecReferenceWalk.java`.
* `TypeSpec.referencedClassNames()` and the four private helpers only it calls (`collectTypeReferences`, `collectAnnotations`, `collectCode`, `collect`), plus imports left unused. `CodeBlock.args()` stays: `CodeWriter` reads it.
* `graphitron-javapoet/src/test/java/no/sikt/graphitron/javapoet/TypeSpecReferencedClassNamesTest.java`. Its cases (structured references inside `$L` blocks, anonymous classes, annotations and bounds) exist to prove the walk sees what javac sees; the index's unit tests below cover the same ground against javac directly.
* `MultiSchemaPipelineTest`'s private `referencedClassNames(TypeSpec)` helper is a different method (method signatures only, for jOOQ classes) and stays.

## Tests

* **Unit tier, `JavacReferenceIndexTest`**, over small hand-written sources in two generated packages plus one non-generated class, each case naming the exact set expected:
  * a same-package simple-name reference is found;
  * a reference inside a method body, a lambda, an anonymous class and an annotation value is found;
  * a reference to a nested type counts as its top-level unit;
  * a use of another unit's `static final String` constant is found (it is absent from the class file);
  * a reference reached only through an inferred type (`foo(Gen.make())` where `Gen.make()` returns another generated unit that is never named) is found;
  * self-references and references to non-generated classes are excluded;
  * a source that does not compile fails with its diagnostics.
* **Pipeline tier, the harness:** `threeLegOracle_projectedGraphMatchesTheEmitArtifact` passes against the javac index once the `GRAPHITRON_CONTEXT` edge is added. Two negative tests make the legs' teeth visible, each wrapping the projected graph in a one-edge decorator over `CompileDependencyGraph`:
  * dropping `Language` to `Film` (the same-package `$project` call the corpus comment names) makes `gaps` return exactly that edge, and dropping the new `GraphitronContext` to `TenantConnections` edge makes it return exactly that edge, the case the walk could not see;
  * adding an edge between two units that do not reference each other and are not in the declared superset makes `undeclared` return exactly that edge.
* `PlanCompileGraphTest` gains a case, in the shape of `nodeFetcherAndEntityDispatchCarryTheirFixedEdges`, pinning the `GraphitronContext` to `TenantConnections` edge as precise: present in the graph and absent from the declared superset.

## Documentation

No user-facing change. Contributor-facing javadoc moves with the code:

* `IncrementalCompileHarnessTest`'s class javadoc names javac as the oracle in leg 2 and leg 3 and points at `JavacReferenceIndex` and `CompileGraphOracle`.
* `GeneratedUnits`' class javadoc says the `{@link}`ed `TypeSpecReferenceWalk` "completeness oracle pins these against the real emitted file set" (line 13). What pins naming against the emitted set is leg 1 (projected nodes equal emitted units), which reads no walk; the sentence is restated to name the harness's oracle in prose (`{@code IncrementalCompileHarnessTest}`, a test-source class, so not a `{@link}`). The `verify`-phase `{@link}` gate fails the build if the link is left dangling.
* `PlanCompileGraph`'s class javadoc lists the precise edges by family ("the facade's schema-class and runtime reads, the node dispatch pair, ..."); it gains the context interface's carrier read. The `addFixedKindEdges` javadoc's sentence on the self-contained scaffolds that contribute no outbound edge no longer covers `GRAPHITRON_CONTEXT`, and is checked against the new arm. Its and `CompileDependencyGraph`'s mention of "the three-leg oracle in the incremental harness" stays true.

## Retired vocabulary

* `TypeSpecReferenceWalk`
* `referencedClassNames` (the `TypeSpec` method; `MultiSchemaPipelineTest`'s private helper of the same name is unrelated and stays)
* "review-only residual" and "literal-FQCN scan", the walk's javadoc terms for its blind spot and its string scan

The Done-gate sweep greps main and test sources, `.adoc` files and roadmap bodies for these. Hits in `roadmap/changelog.md` and the dated audit under `roadmap/audits/` are historical and stay.

## Relation to other items

* **R174** (Spec), replace the fork with Palantir's JavaPoet: depends on this item, which removes the one fork addition Palantir's API cannot host. R174's own sweep confirms nothing reintroduces the retired names.
* **R559** (Backlog), fetcher-to-`TenantConnections` recompile edge parity in tenancy builds: the same class of connection-runtime edge, for different sources, in a tenancy-configured corpus this item does not add. Once R559 adds that fixture, this item's javac oracle is what enforces its verdict. The `GraphitronContext` edge here is not R559's: it fails this item's own oracle on the existing corpus, so it is fixed here.

## Other solutions we've considered

* **Scan the class files (jdeps or ASM).** No compile API needed, but javac inlines compile-time constants at the use site, so a reference through a constant leaves no trace in the class file.
* **Keep the walk and add javac as a fourth leg.** Keeps both oracles honest against each other, but keeps `referencedClassNames()` in the fork, which defeats the goal; and the prototype shows the walk adds nothing javac lacks.
* **Move the walk into test sources but keep `referencedClassNames()`.** Removes it from the emitter's module, but the method still needs `CodeBlock.args()`, which Palantir keeps package-private, so R174 stays blocked.
* **Hook the listener into `IncrementalCompileEngine`.** Reuses the dev loop's own compile, but adds a main-code seam whose only user is a test.

## Reviewer findings

### Round 1 (2026-10-27, Spec -> Ready, reviewer session 01JD78h7sQVcWHBqWJ6mi1SH)

Verdict: Ready. The goal stands on its own (a better-founded check for the incremental compiler, and the fork addition that blocks R174 removed), and the plan stays in test sources rather than adding a main-code seam. Checked against the tree: `TypeSpecReferenceWalk`'s only caller is the harness test; `GraphitronContextInterfaceGenerator` names the carrier through `ClassName.bestGuess`; `CodeBlock.args()` is package-private in the fork; `GRAPHITRON_CONTEXT` is outside `FROZEN_SCAFFOLD_KINDS`; `MultiSchemaPipelineTest`'s helper of the same name walks `TypeSpec` members itself and does not call `referencedClassNames()`; the harness writes the run's sources to disk under its temp directory, so the index can compile the files the dev loop compiles; the retired terms appear only in the walk, this item and the changelog. Two notes for the implementer, neither blocking:

- Leg 2 keeps exempting frozen-scaffold units as sources, and javac will see more of their outbound edges than the walk did, now including `ErrorRouter` to `TenantConnections` (the `isMountFailure` call a rejected session mount added). The exemption is sound only while every member a frozen unit calls on a schema-variant unit keeps one signature across schemas and configurations, which holds today. The index makes that cheap to watch: assert the frozen sources' outbound edges against a pinned list, so a new frozen-to-variant edge shows up as a reviewed diff rather than passing silently. Worth doing if it costs a line; otherwise leave it.
- The index's compile classpath is the harness's existing `runtimeClasspath()`, the one Clause A's engines use; naming it keeps the two compiles from drifting apart.

