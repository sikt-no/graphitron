---
id: R455
title: "Fix TypeSpecReferenceWalk blind spots that falsify the compile-graph completeness oracle superset guarantee"
status: In Review
bucket: bug
priority: 2
theme: dev-loop
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

## Plan

Two coupled workstreams, landed model-first: the walk fix alone turns the harness oracle red on today's corpus (the superset violation is real today), so the model graph grows first and every trunk commit stays green.

What the emitted `types.*` classes actually reference (verified against the emitters):

- The composed child projection (`Target.$fields(...)` inside the inline multiset / lookup subquery) for every inline-emitted `ChildField.TableField` and `ChildField.LookupTableField`, including ones nested under `ChildField.NestingField` sub-trees. Nested inline fields emit into the *outer* table type's class (`TypeClassGenerator.emitSelectionSwitch` recursion), so the edge attaches to the hosting type class, not the field's immediate `parentTypeName()`.
- `util.NodeIdEncoder` and `schema.GraphitronClientException` when `CompositeDecodeHelperRegistry` lifts a decode helper onto the class (an inline field's filter param decodes a `@nodeId` argument).
- `InlineColumnReferenceFieldEmitter` (scalar column subquery, no `$fields`) and the `ComputedField` arm (external user class via `ClassName.bestGuess`) reference no generated unit.

Note that the demonstrated `types.Film → types.Language` edge is a `$T` `ClassName` inside a nested `$L` block (`InlineTableFieldEmitter.java:74`): once the structured walk recurses into `$L` blocks, blind spot #3 (same-package simple names) closes *structurally* for every real emitter shape — the same-package aspect only ever mattered because the fallback nets work on rendered text.

### Workstream B: model the missing edges (`CompileDependencyGraphBuilder`)

Today the builder sources every field edge from the parent's `<Type>Fetchers` unit, and the frozen-scaffold blanket covers only fetcher nodes; `types.*` units are pure nodes with no outgoing edges.

- Add a **separate top-down projection walk** mirroring `TypeClassGenerator`'s seam exactly: filter `TableType`/`NodeType` parents, walk their children, recurse into `NestingField.nestedFields()`, and attribute every edge to the hosting type class. Do *not* bolt this onto `addFieldEdges`: its flat `schema.fields()` iteration keys edges off the field's immediate parent and cannot recover the hosting type class for nesting-hosted fields without re-deriving nesting ancestry (principles-architect consult, 2026-07-09).
- The walk's per-child dispatch is a **no-`default` exhaustive switch over the `ChildField` leaves**, following `addFieldEdges`' discipline rather than `emitSelectionSwitch`'s `default -> {}`: a future inline-projecting leaf fails to compile here until its edge contribution is declared. Today the projecting set is exactly `{TableField, LookupTableField}` plus `NestingField` recursion; every other leaf gets an explicit empty arm.
- Edges contributed per hosting type class: `typeClass(host) → typeClass(target)` for each reachable inline `TableField` / `LookupTableField`; `typeClass(host) → NodeIdEncoder` precisely when a reachable inline field's filter decodes a `@nodeId` composite (the same model predicate `CompositeDecodeHelperRegistry` lifts on — precise because `NodeIdEncoder` is the `PerTypeGrowing` singleton); blanket `typeClass(host) → GraphitronClientException` justified by its existing `UtilSingleton.FrozenScaffold` classification (frozen target → an over-approximated edge never fires a recompile).
- Record the producer-consumer linkage with javadoc `{@link}`s between the projection walk and `emitSelectionSwitch`. The shared predicate "does this child inline-project a type class, and what target" is a capability-lift candidate (`InlineProjectingField` with `projectedTypeName()`, implemented by `TableField`/`LookupTableField`, read by both consumers); name it in the code as the collapse target but do not block this item on it.
- `CompileDependencyGraphBuilderTest`: unit coverage for each new edge shape, including nesting-hosted attribution.

### Workstream A: make the walk see what it claims to (`graphitron-javapoet`)

- `TypeSpec.collectCode`: recurse into `$L` args that are `CodeBlock` (recursively), `TypeSpec` (anonymous classes, via `collectTypeReferences`), or `AnnotationSpec` (via `collectAnnotations`). Today only `instanceof TypeName` args are inspected.
- `collectTypeReferences`: walk `type.typeVariables` and `method.typeVariables` declarations (`collect` already descends `TypeVariableName` bounds when reached, but declarations never are).
- `collect`: guard `TypeVariableName` recursion with a visited set — `T extends Comparable<T>` bounds are self-referential and would recurse infinitely once declared type variables are walked.
- Correct the `referencedClassNames` javadoc: nested `$L` blocks and type-variable bounds are now visible; the remaining blind spot is class names baked into *raw strings* (`$L` string / `$S` args), which are not structured references.
- Unit tests in `graphitron-javapoet` (`TypeSpecTest` or a dedicated test): `$T` inside a nested `$L` `CodeBlock` at depth ≥ 2, `$L` `TypeSpec`, `$L` `AnnotationSpec`, type- and method-level type-variable bounds, recursive-bound termination.

### Oracle residual (`TypeSpecReferenceWalk`)

Keep net 2 (the FQCN-literal scan) as-is; do **not** add a same-package simple-name literal scan — it over-collects (schema type-name string literals such as `b.name("Language")` would demand spurious model edges). After workstream A, the true residual shrinks to "a generated type's simple name baked as a raw *code-bearing string* in a same-package unit", which no emitter produces today. Document this in the walk's javadoc as a **review-only residual** with a discovery recipe (when adding an emitter that bakes code as raw strings, use the FQCN form or extend the oracle), not as a guaranteed contract — nothing enforces the "no emitter does this" premise, and prose that reads as guaranteed goes silently false. If a cheap enforcer turns out feasible during implementation (e.g. an assertion over emitted code-position string literals), prefer it; otherwise the honest label stands.

### Harness corpus extension (`IncrementalCompileHarnessTest`)

- The existing `Film → Language` `@reference` already exercises nested-`$L` same-package projection composition once the walk sees it; the oracle test flipping from false-green to true-green on the unchanged corpus is itself the acceptance for the demonstrated defect.
- Add: an inline reference field with a `@nodeId`-decoding filter argument (exercises `typeClass → NodeIdEncoder` and the decode-helper lift) and a nested plain-object (`NestingField`) carrying an inline table field (exercises hosting-class attribution).
- `MultiSchemaPipelineTest`'s R78 sub-package guard also consumes `referencedClassNames` and gets strictly deeper coverage for free; verify it stays green (a new failure there would be a real latent R78-shape reference surfaced by the fix, to be fixed as such, not a regression of this item).

### Landing order

1. Workstream B (model edges): oracle stays green, graph grows, superset-safe.
2. Workstream A + oracle-residual javadoc + corpus extension: the walk now finds the edges B added; the harness proves the demonstrated false green is closed.

Commit structure within that order is the implementer's judgment.

## Relationship to other items

R410 (shipped; see `changelog.md`) built the oracle and accepted "offline oracle only" as a residual — that residual is about the guard being offline, not about the walk being unsound; this defect is orthogonal. R333 (`coordinate-lowers-to-datafetcher-queryparts`, Spec) and R432 plan a level-1 method-graph oracle that **reuses `TypeSpecReferenceWalk` as-is**, so they inherit these blind spots; fixing the walk here de-risks that reuse rather than being subsumed by it.

Confirmed high-value by the architecture-trap audit (mechanics verified with compiled probes against the real library; the false-green demonstrated live on the R410 harness schema).
