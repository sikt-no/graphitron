---
id: R462
title: "Model the nested fetcher own outgoing per-field precise edges in CompileDependencyGraphBuilder"
status: Spec
bucket: bug
priority: 3
theme: dev-loop
depends-on: []
created: 2026-07-10
last-updated: 2026-07-13
---

# Model the nested fetcher own outgoing per-field precise edges in CompileDependencyGraphBuilder

Specced 2026-07-13, from the Backlog stub R459 filed as its scope-boundary follow-up.

## Problem

R459 registered the `<Type>Fetchers` *node* for a fetcher-owning plain-object nesting type and got the
schema-shape-to-fetcher wiring edge (`FilmMetaType → FilmMetaFetchers`) for free. It deliberately did
not model the nested fetcher's own *outgoing* per-field edges: a nested `SplitTableField`'s DataLoader
methods reference the target type's projection class; a nested composite / `@nodeId` read references
`NodeIdEncoder`. `CompileDependencyGraphBuilder.addFieldEdges` never sees these fields because they are
absent from `schema.fields()` (and `schema.fieldsOf(nestedType)` is empty for a coordinate-less nesting
type). The `TypeSpecReferenceWalk` completeness oracle will flag any such edge as a superset gap once a
harness fixture exercises it.

This is not a routing one-liner. Folding a nested `SplitTableField` through `addFieldEdges`'
`TableTargetField` arm would call `addConditionsEdge(fetcher, "FilmMeta")`, whose `hasSqlGeneratingField`
reads the empty `schema.fieldsOf("FilmMeta")` and produces the wrong answer, the same `fieldsOf`
blindness over coordinate-less nested types that caused the R459 bug. Conditions attribution (and, more
broadly, per-field edge sourcing) over nested types needs its own design that does not depend on the
nested type's SDL coordinates being populated.

## Context, verified 2026-07-13

- **The emitted nested universe is closed.** `GraphitronSchemaValidator.NESTED_WIREABLE_LEAVES` rejects
  every leaf outside {`ColumnField`, `CompositeColumnField`, `TableField`, `LookupTableField`,
  `NestingField`, `SplitTableField`, `SplitLookupTableField`} at nested depth at validate time, so the
  builder's nested per-field sourcing only ever sees those seven live.
- **Nested fetcher outgoing references, traced through the emitters.** `SplitTableField` /
  `SplitLookupTableField` rows methods (`SplitRowsMethodEmitter`) project `types.<ReturnType>` via
  `$fields(...)`; a `GeneratedConditionFilter` is called as
  `ClassName.bestGuess(filter.className())` = `conditions.<ReturnType>Conditions`
  (`FkTargetConditionEmitter.emitTerm`); a `@nodeId`-decoding filter argument lifts a decode helper
  onto the fetcher class (`CompositeDecodeHelperRegistry.collectInto` in `TypeFetcherGenerator`),
  referencing `NodeIdEncoder`. Encoded reads (`ColumnField` with `NodeIdEncodeKeys` compaction,
  `CompositeColumnField`) reference `NodeIdEncoder` via `FetcherEmitter.bindRaw`, uniformly at any
  depth. Inline `TableField` / `LookupTableField` and inner `NestingField` reads reference no
  generated unit from the nested fetcher (source pickups); their projections and inline-filter edges
  land on the *outer* type class, already modeled by the R455 projection walk's `NestingField`
  recursion, and R459's In Review verification pinned this empirically (disabling the R459 walk left
  exactly the one wiring gap). `FetcherRegistrationsEmitter.nestedBody` references only the nested
  fetchers class; frozen scaffolds and `GraphitronContext` are blanket-covered because R459 registers
  the node before `addBlanketAndWiringEdges` runs.
- **Conditions attribution truth.** Only *root* fetchers reference a parent-named conditions class
  (the `QueryConditions` / `MutationConditions` env-shim layer, `QueryConditionsGenerator`). A child
  fetcher references `GeneratedConditionFilter.className()` = `conditions.<ReturnType>Conditions`
  directly (pre-resolved by `FieldBuilder` at classify time). The child `TableTargetField` arm's
  `addConditionsEdge(fetcher, parentTypeName)` therefore models a reference that is never emitted, and
  *misses* the real one for any top-level split-with-filter whose parent and return type names differ,
  plus the fetcher's decode-helper `NodeIdEncoder` reference. Corpus-blind today: the harness's only
  filtered reference field (`Language.films`) is inline, where the R455 projection walk attributes
  correctly.
- **The root shim's own composition is unmodeled.** `QueryConditions.<field>Condition` calls the
  entity-scoped `<ReturnType>Conditions` methods and lifts decode helpers onto the shim class, so
  `conditions(root) → gcf.className()` and `conditions(root) → NodeIdEncoder` edges exist in the emit
  and are absent from the graph. Same corpus blindness (no filtered root field in the harness).
- `MapCompileDependencyGraph.Accumulator.addEdge` auto-registers both endpoints as nodes, so
  filter-sourced conditions edges need no separate node registration.

## Design (settled with principles-architect, 2026-07-13)

- **One filter seam.** Generalize `addInlineFilterEdges(source, filters)` (logic unchanged: per
  `GeneratedConditionFilter` add `source → gcf.className()`; if `filtersDecodeNodeId` add
  `source → NodeIdEncoder`) so `source` may be a type class, a fetcher, or a conditions shim. It stays
  the single enforcer of the filter-to-edges projection; no second copy is written.
- **One per-leaf routine, two callers.** Extract the `ChildField` dispatch of `addFieldEdges` into a
  routine parameterized by the sourcing fetcher node, called from both the top-level `schema.fields()`
  loop and the nested walk. The nested walk stays pure iteration over the same reachability
  `addNestedFetcherNodes` already walks (extend that walk or mirror it); no second per-leaf switch
  exists, so per-leaf edge logic cannot diverge between depths.
- **Child conditions re-attribution.** In the child `TableTargetField` arm, replace
  `addConditionsEdge(fetcher, parentTypeName)` with `addInlineFilterEdges(fetcher, f.filters())`. This
  removes the child-side `hasSqlGeneratingField` / `fieldsOf` dependence entirely, which is exactly
  what makes coordinate-less nested types attributable: the pre-resolved model fact (the filter carries
  its class name) replaces the SDL-coordinate lookup. Removing the parent-named edge is superset-safe:
  it corresponds to no emitted reference (it is either spurious or coincides with the filter-sourced
  edge when parent and return names coincide). `hasSqlGeneratingField` remains only for root conditions
  node and edge gating, where the parent-named shim really exists.
- **The `addTypeClassEdge` stays uniform** over all eight `TableTargetField` leaves at both depths.
  For the inline `TableField` / `LookupTableField` leaves the fetcher-side `typeClass` edge is a
  tolerated over-approximation (the real fetcher-side reference set of an inline read is empty; the
  projection lives on the outer type class). Uniformity dissolves the outer/nested asymmetry a forked
  switch would otherwise need its own pin for; tightening the over-approximation is a pruning
  refinement, out of scope, and unpinnable by the oracle (which enforces only the superset direction).
- **Root shim composition edges.** Root arms keep the parent-named `addConditionsEdge` and additionally
  call `addInlineFilterEdges(units.conditions(parentTypeName), f.filters())` for exactly the arms
  `QueryConditionsGenerator` collects (`QueryTableField` / `QueryTableInterfaceField` today; the
  implementer mirrors the generator's collection set, and the mutation-shim analogue where present).
- **The derived view stays deferred.** R459's named collapse target (nested fetcher-owning types and
  fields as a derived view on `GraphitronSchema`) is not needed for correctness here: edges come off
  the field records during the existing walk, and conditions attribution no longer needs a non-blind
  `fieldsOf`. The collapse target stays named for a future item (now four re-walks of the
  `NestingField` tree: emitter, registrations emitter, projection walk, builder).

## Failing pins (harness, extend both SCHEMA and SCHEMA_EDITED identically)

1. Nested split, no filterable args:
   `FilmMeta.languages: [Language!] @reference(path: [{key: "film_language_id_fkey"}]) @splitQuery`
   pins `FilmMetaFetchers → types.Language` in the completeness oracle.
2. Top-level split re-attribution:
   `Language.filmsSplit(filter: FilmFilter): [Film!] @reference(path: [{key: "film_language_id_fkey"}]) @splitQuery`
   pins `LanguageFetchers → conditions.FilmConditions` and `LanguageFetchers → util.NodeIdEncoder`
   (`FilmFilter.ids` decodes `@nodeId`).
3. Root shim composition: `Query.filmsByFilter(filter: FilmFilter): [Film!]` (plain table read) pins
   `conditions.QueryConditions → conditions.FilmConditions` and `→ util.NodeIdEncoder`.

Pin 1 deliberately carries no filterable args: a `GeneratedConditionFilter` on a *nested* field trips
the separate emit bug filed as R472 (see Scope). Exact SDL shapes are the implementer's discretion if
classification differs from the sketch; the pinned edges are the contract. Each pin is verified
non-vacuous R459-style: with the new edge code disabled, the oracle reports exactly the expected gaps
and nothing else (record the verification in the In Review note).

## Tests

- Unit tier (`CompileDependencyGraphBuilderTest`, hand-built records): nested `SplitTableField` yields
  `fetcher → typeClass` plus, with a `GeneratedConditionFilter`, `fetcher → gcf.className()` and
  `fetcher → NodeIdEncoder` when its filters decode `@nodeId`; nested encoded `ColumnField` /
  `CompositeColumnField` yield `fetcher → NodeIdEncoder`; a top-level child split with a
  `GeneratedConditionFilter` asserts `fetcher → gcf.className()` present *and* the parent-named
  conditions edge absent (pins the re-attribution); root shim edges as designed.
- `CompileDependencyGraphPipelineTest.rootAndChildFetchersReferenceTargetProjectionsAndConditions`
  expects `FilmFetchers → conditions.FilmConditions` for the filterless inline `Film.language`; that
  edge is an artifact of the removed over-approximation and the expectation updates to the new truth.
- `IncrementalCompileHarnessTest` clauses (a) and (b) stay green over the extended corpus.

## Scope

- In: the builder changes above, tests, and the harness fixture extensions. The child conditions
  re-attribution and the root shim edges are in scope because they are the same seam and the same
  design fork the Problem section demands ("conditions attribution ... needs its own design"); they
  share the generalized `addInlineFilterEdges` and the harness pins.
- Out, filed as **R472** (`nested-generated-condition-filters-never-emitted`):
  `TypeConditionsGenerator`'s walk (`schema.types()` × `fieldsOf`) cannot see nested fields, so a
  `GeneratedConditionFilter` on a nested split or inline field emits a call to a
  `<ReturnType>Conditions` method that is never generated (consumer javac failure) — a validator-mirror
  gap. This item's nested filter-sourced edge code is still written (unit-tier covered) and becomes
  live once R472 lands; the harness fixture avoids tripping the bug until then.
- Out: tightening the inline-leaf fetcher-to-typeClass over-approximation (named in Design).
- Observation recorded, no item filed: `ChildField.ServiceTableField.filters()` is never read by any
  emitter (an inert model fact; the leaf cannot appear at nested depth). Surfaced for a future triage.

## Notes

Filed by R459 as its scope-boundary follow-up. R459's related collapse target (the derived
nested-type view on `GraphitronSchema`) is deliberately not built here; see Design.
