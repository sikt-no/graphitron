---
id: R336
title: "Flatten nested input-object fields in jOOQ-record @service params"
status: Backlog
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

## Scope sketch (firm up in Spec)

* **Model.** `CallSiteExtraction.ColumnBinding` and `RecordKeyDecode` each carry a single `sdlFieldName` (one top-level Map key). Flattening needs an ordered access *path* so the emitter can descend through nested Maps. Either widen those two records with a path field, or wrap them analogously to `NestedInputField`. Decide which keeps the at-least-one-binding floor and the two existing single-key call sites cleanest.
* **Resolver.** `buildJooqRecord` recurses into nested input-object fields, flattening their leaves onto the *same* `TableRef`. Needs: cycle detection (a nested input that reaches itself); a decision on list-valued nesting (reject vs support); the `@table`-on-nested-input rejection (a nested grouping must not itself carry `@table`); and binding-key/column collision detection across the now-flattened namespace (two leaves resolving to one column).
* **Emitter.** `JooqRecordInstantiationEmitter` (the `create<Record>` / `create<Record>List` helpers) traverses the path null-safely instead of reading `raw.get(key)` at a single level, for both `ColumnBinding` set and `RecordKeyDecode` decode.
* **Tests.** Pipeline tier alongside `JooqRecordServiceParamPipelineTest` (flattened column bindings, nested `@nodeId`, cardinality, rejections); classification/corpus coverage for the nested grouping input.

## Notes from triage

The secondary report observation, that the outer input "classified as PojoInput", did not reproduce: the record-param (outer) input is correctly `JooqTableRecordInputType`, and the reported error only fires on that classification path, so the outer must be classified that way at codegen. Only the nested grouping input is `PojoInput`. Whether a nested grouping input should remain `PojoInput` or gain a dedicated input-side "nesting projection" classification (the input analogue of `NestingType`) is a separate concern; relate to R171 (`input-like-type-sealed-parent`) if pursued.

**Out of scope:** multi-table nesting; reclassifying the nested grouping type; any change to the `@table`-input nesting path.
