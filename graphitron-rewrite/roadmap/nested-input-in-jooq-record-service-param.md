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

### Semantics: transparent unpack

A nested grouping input behaves exactly as if its fields were declared directly on the parent. For every leaf field, at any nesting depth:

* **omitted** → its column is left `changed=false` (untouched, excluded from the INSERT/UPDATE the `@service` runs);
* **present and `null`** → its column is set to `NULL` (`changed=true`);
* **present with a value** → its column is set to that value (`changed=true`).

A nested group that is itself absent, `null`, or not a `Map` is treated identically to "absent": every column under it stays untouched, so `details: null`, an omitted `details`, and `details: {}` all reach the same outcome. This is the column-axis extension of the existing top-level three-way (`JooqRecordInstantiationEmitter.emitColumnBinding`): a top-level field and a nested field backing the same column behave identically.

**Required-ness is owned by graphql-java, not by this flatten.** A nested grouping field's own nullability is the switch. A non-null group (`details: FilmDetailsInput!`) is mandatory: per the GraphQL spec a Non-Null argument or input-object field that is "not provided, ... or ... provided the literal value null" raises a request error at the execution boundary, and when the group *is* present its own non-null fields are enforced in turn. A nullable group is optional: the spec's required-field check ("if no default value is provided and the input object field's type is non-null, an error should be raised") is a step *inside* coercing an input-object value, so it fires only when the enclosing object is actually coerced; a nullable parent that is omitted or `null` is never descended into ("if the field is not required, then no entry is added to the coerced unordered map"). Consequence: by the time the generated `create<Record>` helper runs, graphql-java has already enforced every required field that should be present. The helper therefore **never throws for a missing nested field**; it skips a binding whose enclosing group is absent, and throws only on a *malformed id it actually attempts to decode* (R195, possible only when the group and the id are both present).

### D1: Access path on the column-axis carriers

Replace the single `sdlFieldName` on `CallSiteExtraction.ColumnBinding` and `RecordKeyDecode` with an ordered, non-empty `List<String> path`: the last element is the leaf SDL field name (the `Map` key), earlier elements are the enclosing nested-input field names. A top-level binding carries a single-element path; the nested `details.title` carries `["details", "title"]`.

This adopts the representation `CallSiteExtraction.NestedInputField` already settled (R186): an ordered, non-empty key path, single-element for the top-level case. The earlier path elements drive the emitter's parent-`Map` descent (D4) and the last element is the leaf `Map` key read from the bound parent map; at depth 1 there is no enclosing group, the descent is empty, and emission is byte-identical to the current top-level form. The outer-argument name stays *outside* the bindings (carried by the enclosing `ValueShape.JooqRecordInput(jr, path)`), so the per-binding path is correctly scoped to "keys from the record's own `Map` down to the leaf," with no `outerArgName` to duplicate.

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

### D4: Emitter parent-`Map` descent and collision-free locals

For a binding whose `path` has more than one element, `JooqRecordInstantiationEmitter` wraps the existing per-binding emission in a descent that binds the leaf's enclosing `Map` and short-circuits when any ancestor is absent, `null`, or not a `Map`:

```java
if (raw.get("details") instanceof Map<?, ?> detailsMap) {   // group present and a Map?
    // the current top-level emission, verbatim, with raw -> detailsMap and the leaf key "title":
    if (detailsMap.containsKey("title")) { ... }
}
// group absent / null / not-a-Map -> block skipped -> columns under it untouched
```

This one construct realises every rule in *Semantics* at once: a `null`/absent group fails the `instanceof Map` and skips everything beneath it, and a non-null identity field under an *absent nullable* group is skipped rather than thrown (its R195 throw lives in the body, which is never entered). The descent reuses the `instanceof Map<?,?>` chain idiom already in `ArgCallEmitter` (generalised for paths deeper than two); the per-binding set/decode body is otherwise unchanged, reading `parentMap.containsKey(leaf)` / `parentMap.get(leaf)` exactly as today's code reads `raw.containsKey(sdlFieldName)` / `raw.get(sdlFieldName)`. At depth 1 the `path` is single-element, no wrapping block is emitted, and the output is byte-identical to today.

The per-binding local names must derive from the full `path`, not the leaf name alone: the bound parent-`Map` local (e.g. `detailsMap`) and all three leaf locals (`<x>Value`, `<x>Keys`, and `<x>Raw` on the nullable-decode arm). Otherwise two nested groups sharing a leaf name (`title`) would emit colliding `titleValue` / `titleRaw` locals in one helper body. Bindings sharing a parent group may share one wrapping descent block (grouped by parent path) or each re-descend; that is an In-Progress emitter nicety, not a contract.

### Untouched (verified clean)

The at-least-one-binding floor (the flatten only adds bindings, never removes) and the `create<Record>List` plural path (outer-record cardinality, orthogonal to inner nesting) need no change.

The per-decode `nonNull` flag and its null-vs-set semantics are deliberately *not* in this list. The set/decode body is reused unchanged, but it now runs against the descended parent `Map` (D4), and the non-null identity arm newly gains the group-presence guard so an absent nullable group skips rather than throws (*Semantics*, required-ness rule). That is the column-axis behavior change this item introduces, not an invariant left alone; the earlier "source unchanged at depth" framing was wrong.

### Test plan

* **Pipeline tier** alongside `JooqRecordServiceParamPipelineTest`: assert the classified `JooqRecord` carries the expected `ColumnBinding` / `RecordKeyDecode` `path`s (flattened plain columns; a nested `@nodeId` decode; mixed top-level + nested). No body-string assertions on the descent chain.
* **Execution tier**: a round-trip proving a nested `details.title` lands in the right column on the generated INSERT/UPDATE, plus the transparent-unpack semantics: an omitted nested leaf leaves its column `changed=false` (the partial-update contract, untouched by the service's UPDATE), a present-`null` nested leaf writes `NULL`, and a `null`/omitted nullable group leaves every column under it untouched.
* **Skip-not-throw**: a non-null identity field inside an omitted nullable group is skipped with no decode error raised (*Semantics*, required-ness rule); a *malformed* id in a *present* group still throws.
* **Rejection tests** by message substring for each D3 invariant (cycle, list-valued nesting, nested `@table`, plain-column collision).

## Notes from triage

The secondary report observation, that the outer input "classified as PojoInput", did not reproduce: the record-param (outer) input is correctly `JooqTableRecordInputType`, and the reported error only fires on that classification path, so the outer must be classified that way at codegen. Only the nested grouping input is `PojoInput`. That nested grouping is semantically a column-projection of the parent's table (the input mirror of the output-side `NestingType`), and surfacing it as `PojoInput(null)` is a model/LSP-honesty wart split out as **R337** (`input-nesting-projection-classification`). This item does **not** depend on R337: the flatten below reads the nested type's SDL fields directly, so codegen works regardless of the nested type's classification.

**Out of scope:** multi-table nesting; reclassifying the nested grouping type; any change to the `@table`-input nesting path.
