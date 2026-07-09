---
id: R455
title: "Fix TypeSpecReferenceWalk blind spots that falsify the compile-graph completeness oracle superset guarantee"
status: Backlog
bucket: bug
priority: 2
theme: structural-refactor
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Fix TypeSpecReferenceWalk blind spots that falsify the compile-graph completeness oracle superset guarantee

## Symptom

The R410 incremental-compile completeness oracle (`TypeSpecReferenceWalk`) exists to guarantee that the model-projected `CompileDependencyGraph` is a **faithful superset** of every reference the emitted code actually contains: `walkEdges(u) ⊆ modelGraph.directReferences(u)`. The walk cannot see several reference shapes, and neither can its fallback FQCN scan, so the guarantee is silently false. When it holds false for an edge, the dev-loop incremental compiler can prune a dependent that an ABI change should have recompiled: a **silent, hard-to-reproduce miscompile** the oracle was built to prevent, and the oracle test stays green because the walk is blind to the very edge the model graph is missing.

## Blind spots (verified empirically against the real library)

- **`$T` inside a nested `$L` CodeBlock is invisible.** `TypeSpec.referencedClassNames`' `collectCode` (`graphitron-javapoet/.../TypeSpec.java:185-190`) only inspects `code.args()` elements that are `instanceof TypeName`. A `CodeBlock` passed as a `$L` arg is stored opaque (`CodeBlock.Builder.argToLiteral`; `addStatement(CodeBlock)` wraps it as `$L`), so every `$T` nested inside it is missed. Generators nest blocks as `$L` args pervasively (e.g. `CompositeDecodeHelperRegistry.java:161`, `FetcherEmitter.java:706/709`, 60+ sites across ~14 files).
- **Type-variable bounds are never walked.** `collectTypeReferences` (`TypeSpec.java:148-174`) never iterates `type.typeVariables` / `method.typeVariables`, so a bounded generic over a generated class (`<T extends GeneratedThing>`) contributes no edge. (R410's slice-3 review added type-variable/bounds coverage to `AbiSignature` but not to the reference walk.)
- **Same-package simple names match neither net.** The second net (`TypeSpecReferenceWalk.java:52-57`) recovers cross-package nested refs via the rendered import line, but a same-package reference renders as a bare simple name with no import and no FQCN, so it is invisible to both nets.

The `TypeSpec` javadoc (`:137-140`) claims the walk is a faithful superset and that only `$L` literal *strings* are invisible; that is false as written.

## Demonstrated live

On R410's own harness schema (`IncrementalCompileHarnessTest`), the emitted `types.Film` contains a real compile-time call `Language.$fields(...)` inside a nested multiset `$L` block (same package `types.*`, bare simple name, no import). A deep-walk-vs-oracle diff finds this `types.Film → types.Language` edge oracle-blind, and the model-sourced graph is missing it **today**: `CompileDependencyGraphBuilder.fromModel` sources projection edges solely from fetchers (`:168-205`), never modeling type-to-type projection composition, so `directReferences(types.Film)` is empty. The superset contract is thus violated on the current corpus and `IncrementalCompileHarnessTest` passes anyway: a demonstrated false green.

(The missing `types.<T>` projection edge overlaps with, but is broader than, the R410 In-Review fix that closed the `@node` node-lookup wiring edges model-sourced in the builder; that fix did not add general type-to-type projection edges.)

## Fix direction (for Spec)

Two coupled gaps to close, to be scoped at Spec:

1. **Make the walk see what it claims to** — teach `referencedClassNames`/`collectCode` to recurse into `$L` `CodeBlock` args (and `TypeSpec`/`AnnotationSpec` `$L` args), walk type-variable bounds, and either resolve same-package simple names or narrow the javadoc'd guarantee to exactly what it can see. A blind oracle is worse than an honest narrower one.
2. **Model the missing edges** — have `CompileDependencyGraphBuilder.fromModel` source type-to-type projection-composition edges, not only fetcher-sourced edges, so `directReferences` actually contains the projection references the walk finds.

Both are needed: (1) restores the oracle's ability to *detect* gaps; (2) closes the specific gap it would then detect. Extend the harness corpus with a nested-`$L`/same-package/bounded-generic fixture so the oracle guards these shapes.

## Relationship to other items

R410 (shipped; see `changelog.md`) built the oracle and accepted "offline oracle only" as a residual — that residual is about the guard being offline, not about the walk being unsound; this defect is orthogonal. R333 (`coordinate-lowers-to-datafetcher-queryparts`, Spec) and R432 plan a level-1 method-graph oracle that **reuses `TypeSpecReferenceWalk` as-is**, so they inherit these blind spots; fixing the walk here de-risks that reuse rather than being subsumed by it.

Confirmed high-value by the architecture-trap audit (mechanics verified with compiled probes against the real library; the false-green demonstrated live on the R410 harness schema).
