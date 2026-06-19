---
id: R336
title: "Flatten nested input-object fields in jOOQ-record @service params"
status: Spec
bucket: architecture
priority: 6
theme: service
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Flatten nested input-object fields in jOOQ-record @service params

`InputBeanResolver.buildJooqRecord` (the R311/R315 path that builds a `CallSiteExtraction.JooqRecord` for a `@service` parameter typed as a generated jOOQ `TableRecord`) walks the SDL input fields of the param's `JooqTableRecordInputType` and recognises exactly two field kinds: a `@nodeId` field (resolved to a `RecordKeyDecode`) and a plain field whose `@field(name:)`/name resolves to a column on the record's table (resolved to a `ColumnBinding`). It has no arm for a field whose SDL type is itself a nested input object. Such a field drops into the plain-field arm, computes its binding key as the field's own name, finds no column of that name, and rejects with `input field '<f>' (binding key '<f>') resolves to no column on table '<t>' backing param record '<R>'; did you mean: …`. This blocks the common "one record built from a grouped input" shape, where a `@service` mutation input groups related scalar columns under nested sub-inputs (status block, validity period, weighting, etc.) that all map to columns on the one backing table.

Reproduced in-tree against the test catalog: input `ModifyFilmInput { filmId: ID! @nodeId(typeName: "Film"), details: FilmDetailsInput! }` with `FilmDetailsInput { title: String @field(name: "title"), releaseYear: Int @field(name: "release_year") }` and a `@service` param typed `FilmRecord`. The outer input classifies correctly as `JooqTableRecordInputType` (table `film`); the field rejects as `UnclassifiedField` with "input field 'details' … resolves to no column on table 'film'". The nested `FilmDetailsInput` classifies as `PojoInputType` (null backing), consistent with how the `@table`-input path leaves a directiveless nested grouping type.

This is the column-axis analogue of a gap the `@table`-input path already solved: a directiveless nested grouping input under a `@table` input flattens onto the parent table via `InputField.NestingField` + `CallSiteExtraction.NestedInputField`, whose leaf carries an access path (`["details", "title"]`) so the emitter descends `raw.get("details").get("title")` null-safely. The jOOQ-record `@service` path needs the same flatten on its own column axis.

## Spec

### D1: Access path on the column-axis carriers

Replace the single `sdlFieldName` on `CallSiteExtraction.ColumnBinding` and `RecordKeyDecode` with an ordered, non-empty `List<String> path`: the last element is the leaf SDL field name (the `Map` key), earlier elements are the enclosing nested-input field names. A top-level binding carries a single-element path; the nested `details.title` carries `["details", "title"]`.

This adopts the representation `CallSiteExtraction.NestedInputField` already settled (R186): an ordered, non-empty key path, single-element for the top-level case. Its emitter dual `ArgCallEmitter.nestedMapValueExpr(mapLocal, path)` degrades **byte-identically** to `mapLocal.get(key)` at depth 1, so existing single-level emission is preserved exactly. The outer-argument name stays *outside* the bindings (carried by the enclosing `ValueShape.JooqRecordInput(jr, path)`), so the per-binding path is correctly scoped to "keys from the record's own `Map` down to the leaf," with no `outerArgName` to duplicate.

Rejected alternatives: a separate `nestingPath` beside a retained `sdlFieldName` splits one fact (the full access path) across two components, forces every consumer to reassemble it, and opens an illegal-state surface the compact constructor must police; literally reusing `NestedInputField` as the carrier does not fit (these are `JooqRecord` sub-records, not `CallSiteExtraction`s, and it would re-duplicate `outerArgName`).

### D2: Parallel column-axis walk, shared cycle context

`buildJooqRecord` recurses into nested directiveless input-object fields itself, mirroring the way the member axis already recurses (`bindField` → `buildInputBean`, same file), and keeps producing column-axis carriers (`ColumnBinding` / `RecordKeyDecode`). It does **not** route through `BuildContext.classifyInputField` (the `@table`/filter axis): that produces a different carrier family (`InputField.*`), runs in a different pass (type pass vs. this field-pass post-step), and resolves different identity semantics (an `@nodeId` filter field decodes to a predicate tuple; an `@nodeId` record field decodes to *loaded target columns*). Keeping the two axes parallel is the existing intentional split (`CallSiteExtraction` member axis vs. column axis); the recursion is that split catching up at the nested level.

The one piece reused rather than re-coined is the cycle guard. The recursion is on SDL nested-input *type names* onto one backing table, so it threads the SDL-type-name "expanding" discipline (`ClassifyContext.expandingTypes`, a `Set<String>` of in-flight nested-input type names), not a third idiom beside that and `buildInputBean`'s `Set<Class<?>> visited`. Threading the full `ClassifyContext` vs. just a `Set<String>` is an In-Progress implementation detail; the requirement is the SDL-name idiom.

### D3: Rejection catalog (validator-mirror)

There is no `GraphitronSchemaValidator` arm for the `JooqRecord` family; its invariants fail at classify time as typed `Rejection`s returned through `JooqBuilt.Fail` → `Resolved.Rejected` (a real build failure). Every new invariant the flatten introduces must produce such a rejection, never a silent skip, last-write-wins, or `StackOverflowError`:

* **Cycle**: a nested input that reaches itself (directly or transitively) rejects structurally, naming the cycle (the column-axis analogue of `buildInputBean`'s recursive-shape reject).
* **List-valued inner nesting**: a nested grouping field that is list-shaped (`details: [FilmDetailsInput!]`) rejects: a single backing record has one value per column, so a list of column-groups is a cardinality contradiction. This is a *new* axis the top-level `elt.list() != sdl.list()` parity check does not see (that governs the whole-record `List<FilmRecord>` parameter, not a list field inside a singular record).
* **Nested `@table`**: a nested input carrying `@table` is a second DML target; reject, mirroring the existing top-level D2 arm (`InputBeanResolver` ~225-234) and citing R122 (compound mutations) as the owner. Do not silently flatten it onto the parent: that erases an authored directive.
* **Plain-column collision**: two plain-`@field` leaves (in any nested group) resolving to the same column reject, mirroring the member-axis binding-key collision reject. Decode-vs-decode and decode-vs-column on a shared column stay with the existing R322 value-agreement deferral (last-write-wins), unchanged.

The existing `buildJooqRecord` javadoc enumerates its R195/R97-shaped rejections; extend that enumeration with these.

### D4: Emitter path descent and collision-free locals

`JooqRecordInstantiationEmitter` replaces its `raw.get(sdlFieldName)` reads with `ArgCallEmitter.nestedMapValueExpr("raw", binding.path())` for both the `ColumnBinding` set and the `RecordKeyDecode` decode, inheriting the null-safe descent (any intermediate non-`Map`/`null` yields an absent leaf). The per-binding local names (`<x>Value`, `<x>Keys` at `JooqRecordInstantiationEmitter` ~99/123) must derive from the full path (e.g. `details_title`), not the leaf name alone, or two nested groups sharing a leaf name (`title`) would emit colliding `titleValue` locals in one helper body.

### Untouched (verified clean)

The at-least-one-binding floor (flatten only adds bindings, never removes), the R315 D4 nullable conditional-set semantics (per-decode `nonNull`, axis-agnostic, source unchanged at depth), and the `create<Record>List` plural path (outer-record cardinality, orthogonal to inner nesting) need no change.

### Test plan

* **Pipeline tier** alongside `JooqRecordServiceParamPipelineTest`: assert the classified `JooqRecord` carries the expected `ColumnBinding` / `RecordKeyDecode` `path`s (flattened plain columns; a nested `@nodeId` decode; mixed top-level + nested). No body-string assertions on the descent chain.
* **Execution tier**: a round-trip proving a nested `details.title` lands in the right column on the generated INSERT/UPDATE.
* **Rejection tests** by message substring for each D3 invariant (cycle, list-valued nesting, nested `@table`, plain-column collision).

## Notes from triage

The secondary report observation, that the outer input "classified as PojoInput", did not reproduce: the record-param (outer) input is correctly `JooqTableRecordInputType`, and the reported error only fires on that classification path, so the outer must be classified that way at codegen. Only the nested grouping input is `PojoInput`. That nested grouping is semantically a column-projection of the parent's table (the input mirror of the output-side `NestingType`), and surfacing it as `PojoInput(null)` is a model/LSP-honesty wart split out as **R337** (`input-nesting-projection-classification`). This item does **not** depend on R337: the flatten below reads the nested type's SDL fields directly, so codegen works regardless of the nested type's classification.

**Out of scope:** multi-table nesting; reclassifying the nested grouping type; any change to the `@table`-input nesting path.
