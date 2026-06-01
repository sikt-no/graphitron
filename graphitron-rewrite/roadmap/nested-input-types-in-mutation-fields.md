---
id: R186
title: Nested input types in @mutation fields
status: Spec
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
created: 2026-05-20
last-updated: 2026-06-01
---

# Nested input types in @mutation fields

Two classifiers structurally reject any `InputField.NestingField` on an `@mutation` input. **R246 and R258 forked the UPDATE path off `MutationInputResolver`** (see [R246/R258-shaped substrate](#r246r258-shaped-substrate) below): *every* `@mutation(typeName: UPDATE)` shape now classifies through `UpdateRowsWalker` (the direct-`@table`/ID-returning shape via R246, the payload-returning shape via R258), which rejects nesting at `UpdateRowsWalker.java:87-90` (`UpdateRowsError.UnsupportedInputFieldShape`, reason *"nested input types in @mutation(typeName: UPDATE) fields are not yet supported"*); INSERT and DELETE still flow through `MutationInputResolver`, which rejects at `MutationInputResolver.java:500-506` (`Rejection.structural`, reason *"nested input types in @mutation fields are not yet supported"*, sibling to the comment "nested-input is R128's compound-entity-mutations territory" at `:498-499`). Neither rejection carries a plan-slug pointer, so consumers hitting this today have no roadmap item to track against. Unlike the sibling `ColumnReferenceField` / `CompositeColumnReferenceField` arms (which point at R24's join-projection work) and unlike the multi-table parent-child INSERT case (R122), a plain nested non-`@table` input that maps onto columns of the same DML target table has no roadmap coverage at all.

## Forcing-function schema

```graphql
input EndreOrganisasjonInput @table(name: "ORGANISASJON") {
  id: ID! @nodeId(typeName: "URegOrganisasjon")
  originalnavn: String! @field(name: "NAVN_ORIGINAL")
  lokalisering: LokaliseringInput!
}

input LokaliseringInput {
  landkode: String!
  bynavn: String
  regionnavn: String
}

type Mutation {
  endreOrganisasjon(input: [EndreOrganisasjonInput!]!): EndreOrganisasjonPayload
    @mutation(typeName: UPDATE)
}

type EndreOrganisasjonPayload {
  organisasjoner: [URegOrganisasjon!]!   # list-shaped data field: the listed input is admitted as a MutationBulkUpdatePayloadField (R141 Invariant #15/#16)
}
```

`LokaliseringInput` is a plain (non-`@table`) input that groups columns of `ORGANISASJON`. There is no second table involved; this is purely a grouping shape on the consumer side. This is a payload-returning bulk UPDATE, so post-R258 it classifies through `classifyUpdatePayloadField` → `UpdateRowsWalker` (as a `MutationBulkUpdatePayloadField`). Today it fails there: `LokaliseringInput` resolves to an `InputField.NestingField`, which the walker rejects at `UpdateRowsWalker.java:87-90`, even though every leaf column targets the same DML table as the outer `@table` input.

## Relationship to neighbouring items

- **R122 (compound mutations)** covers nested inputs that introduce *additional* tables (parent + child INSERT). A non-`@table` nested input that flattens onto the outer table's columns is structurally a different shape and shouldn't have to wait for R122.
- **R24 (`NodeIdReferenceField` join projection)** covers FK-target `@nodeId` carriers, orthogonal axis.
- **R96 (input-type variant reshape)** and **R94 (input-record shape)** ship the per-input-type Java class the validator pre-step walks; the emitted class already recurses into nested input components via `fromMap`, so the validation surface composes without further work.

## Design

### R246/R258-shaped substrate

R246 (Done; changelog `8a04c0b`/`975f593`/`c63c14c`/`f3a39ea`) and R258 (Done; changelog `ac1eee4`) both landed after this item's Backlog draft and together moved *every* `@mutation(typeName: UPDATE)` shape off `MutationInputResolver` onto `UpdateRowsWalker`: R246 absorbed R188's UPDATE-side partition scope and carried the direct-`@table`/ID-returning shape; R258 carried the payload-returning shape. The classifier forks on the DML verb and, for UPDATE, on return-type identity (`FieldBuilder.java:3173-3188`):

* **All UPDATE → `UpdateRowsWalker`.** `FieldBuilder` intercepts `kind == DmlKind.UPDATE` *before* `resolveInput` (`FieldBuilder.java:3182-3188`) and forks on return-type: the direct-`@table`/ID-returning shape via `classifyUpdateTableField` (R246), the payload-returning (`ResultReturnType`) shape via `classifyUpdatePayloadField` (R258). Both build a slim `InputArgRef` plus the `UpdateRows.Identified(matchedKey, setColumns, keyColumns)` carrier; neither carries `TableInputArg`. The walker is a *translator over the already-classified `InputField` permits* (`UpdateRowsWalker.walk` Stage 2 loop, `UpdateRowsWalker.java:68-95`): it switches on the four admitted column carriers and partitions them by **PK-or-UK coverage** read from the jOOQ catalog (column-identity / `sqlName`-driven, `:116-153`), not by `@value`. `@value` is *inert* on every UPDATE path; were one to reach `resolveInput` it would throw `IllegalStateException` (`MutationInputResolver.java:509-521`, mirrored by the unreachable-arm guard at `FieldBuilder.java:3344-3351`).
* **INSERT and DELETE → `MutationInputResolver.resolveInput` → `TableInputArg`.** These are the only two verbs that reach this resolver. The `setFields()` / `lookupKeyFields()` partition (`ArgumentRef.java:289-308`) reads `@value` *only* on its now-dead `kind == DmlKind.UPDATE` branch; the live `else` branch (INSERT/DELETE) ignores `@value` entirely (every admissible leaf → `lookupKeyFields`, `setFields` empty). A consumer who places `@value` on an INSERT or DELETE input field already gets a *hard structural rejection* upstream of the partition (the `acceptsValueMarker()` gate, `MutationInputResolver.java:368-376`).
* **UPSERT → refused** outright at the top of `resolveInput` (`MutationInputResolver.java:314-319`, `Rejection.deferred` → slug `mutation-cardinality-safety-upsert`) under the R144/R145 cardinality-safety regime; it never reaches the per-field admission loop.

So R186 still spans **two flatten-and-admit sites and two rejection sites**, one per substrate, but the membership is now clean: the walker owns *all* UPDATE, `MutationInputResolver` owns INSERT + DELETE. The two admit sites share the same upstream classification and the same `CallSiteExtraction.NestedInputField` wire-path mechanism, and differ only in where the flat leaves land (`UpdateRows` vs `TableInputArg`); downstream they reconverge on a single shared emit seam (see step 5).

### What the classifier already gives us

The classifier already does the hard work, identically for both paths. `BuildContext.classifyInputFieldInternal` (BuildContext.java:1769-1833) recognises a plain (non-`@table`) input object on a `@table`-input field (`:1793-1795`) and recursively classifies its children against the *outer* table's `TableRef` (passed through unchanged at the recursive call, `:1806-1811`). Each leaf returns as the same admissible carrier shape it would carry at the input root (`InputField.ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`), wrapped in an `InputField.NestingField` (`:1831-1833`) that records the SDL grouping. `@nodeId` is dispatched (`:1769-1773`) before the nesting branch, so a `@nodeId`-carrying nested leaf produces the same reference carriers it would at the root. The classifier already rejects unresolvable leaves via `UnclassifiedType` and circular references via "circular input type reference detected" (`:1796-1799`) — both error paths land in `Unresolved` before admission.

The only thing standing between today's classification and a working nested `@mutation` is the two structural rejections (`MutationInputResolver.java:500-506`, `UpdateRowsWalker.java:87-90`) and the downstream emitters' assumption that every input field is reachable as a single `in.get("name")` lookup. The wire-path mechanism for nested access already exists for the query-side condition path: `CallSiteExtraction.NestedInputField(outerArgName, path, leaf)` (`CallSiteExtraction.java:103-119`, constructed at `FieldBuilder.java:1687`/`:1707` for `@condition` implicit body params and rewrapped at `ConditionResolver.java:166` for explicit filters; the record self-guards against a `NestedInputField` leaf at `:110-112`, so deep nesting must collapse to a single carrier with a multi-segment path, never a wrapped chain — see step 3). The mutation emitters are the only consumers that still hard-code a single-segment access.

### Resolution of the Backlog-stage open questions

* **Column-coverage** is already enforced at classification time. The leaf-side classifier returns `Unresolved` for any field whose column can't be reached on the outer table, the parent NestingField then propagates that as an `UnclassifiedType` on the containing input. No new check is needed; reuse what's there.
* **`@field(name:)` scoping**: the outer `@table` is the resolution context, period. `BuildContext.java:1806-1811` passes `resolvedTable` through unchanged when recursing into the nested input, so `@field(name:)` and the SDL-name-defaults-to-column-name fallback both already address the outer table's columns. Document this in the user docs; no code change.
* **Nested + `@nodeId`**: admit. The leaf classifier dispatches on `@nodeId` before the nesting branch (BuildContext.java:1769-1773), so `@nodeId`-carrying leaves under a `NestingField` produce the same carrier shapes (same-table → `ColumnField` / `CompositeColumnField`; FK-target → `ColumnReferenceField` / `CompositeColumnReferenceField`) as they do at the input root. The R189 admission rule (`liftedSourceColumns` on the input's own table) carries over unchanged because the outer table *is* the input's own table.
* **DML verb coverage**: admit on the three verbs that classify today (INSERT, UPDATE, DELETE). The nesting shape is purely a wire-format detail; the DML emit shape (column list, SET clause, WHERE predicate, key coverage) is over the flattened leaves, which are normal carriers. R130's INSERT carve-out on `CompositeColumnField` stays — orthogonal axis. **UPSERT is excluded**: R246/R145 refuse `@mutation(typeName: UPSERT)` outright before the per-field admission loop, so there is no admission path to add nesting to; nested-UPSERT rides along whenever R145 lands UPSERT classification, at no extra cost here. **UPDATE is entirely on the walker substrate** (both the direct-`@table`/ID-returning and the payload-returning shapes, post-R258), admitting through `UpdateRowsWalker` (PK-or-UK partition over the flattened leaves' resolved columns); **INSERT and DELETE** admit through `MutationInputResolver` / `TableInputArg`.
* **List-typed nested inputs**: reject. `nf.list() == true` (e.g. `lokalisering: [LokaliseringInput!]`) has no obvious meaning when flattening onto one outer row, and no forcing-function schema exists. Emit a structural rejection naming this item so the deferral has a redirect.

### Representation choice

Each substrate has one gate between admission and emission: `ArgumentRef.InputTypeArg.TableInputArg.of` (`ArgumentRef.java:277-312`) for the `MutationInputResolver` (INSERT/DELETE) path, and the `UpdateRowsWalker` classify loop (`UpdateRowsWalker.java:68-95`) for the UPDATE path. The choice below is stated for the `TableInputArg` gate; the `UpdateRowsWalker` gate takes the same shape (flatten `NestingField` to its leaf carriers before the carrier switch, rewrapping each leaf's `extraction` as `NestedInputField`), after which the walker's existing PK-or-UK partition runs over the flat leaves' resolved columns unchanged. Two viable representations:

* **A. Add `NestingField` to the `LookupKeyField` / `SetField` sealed permits and have every emitter handle it.** Honest to the SDL shape, but every emit site (`emitSetMapPuts`, `emitSetExcludedPuts`, `emitSetVColNameAdds`, `emitSetBulkCellAdds`, `emitSetVFieldPuts`, and the corresponding `lookupKey` walkers) grows a recursive arm.
* **B. Flatten in the factory.** The factory walks `NestingField` and emits its admissible-carrier leaves into `fields` / `lookupKeyFields` / `setFields` directly, rewrapping each leaf's `extraction` from its current shape into `CallSiteExtraction.NestedInputField(argName, accessPath, originalExtraction)` so the wire access knows to descend. `LookupKeyField` / `SetField` permits stay closed against `NestingField`; the partition stays purely flat-carrier-typed.

Recommend **B**, and the framing is principled rather than convenient: *SDL nesting is a wire-format shape, not a DML shape*. The DML model is flat columns on one table; the partition that drives SET / WHERE / VALUES emit is over flat carriers. `CallSiteExtraction` is the canonical home for wire-decode strategy — `NestedInputField` already lives there for the condition path — and the access path attaches to the leaf's extraction at the boundary between admission and emit, exactly where wire concerns belong. The model's flat-leaf partition is what's *true downstream*; the `NestingField` envelope persists only for consumers that legitimately need the SDL shape (validation diagnostics, LSP hovers), which is why retaining it on `TableInputArg.fields()` reads as faithful preservation rather than half-flattening.

The partition lists (`setFields()` / `lookupKeyFields()`) carry the flat-leaf view. Consumers walk leaves only.

Because the flatten-and-rewrap step is *identical* at both gates — project a `NestingField` to its admissible-carrier leaves, rewrapping each leaf's `extraction` as `CallSiteExtraction.NestedInputField(argName, accessPath, leafExtraction)` — it lives in **one** shared helper (e.g. a static `NestingField.flattenToLeaves(argName)`), which both `TableInputArg.of` and the `UpdateRowsWalker` Stage 2 loop call before their (different) partitions. The gates differ only in the partition that runs *after* the flatten and the carrier it fills (`TableInputArg` vs `UpdateRows`); the flatten itself is one transform with one home. This pins the step-8 access-path invariant in a single place, keeps the two gates from drifting, and makes the DELETE re-homing under R266 (see the interaction note) a one-line call-site move rather than a re-implementation.

### Implementation sketch

1. **`MutationInputResolver` admission** (`MutationInputResolver.java:442-507`) — INSERT and DELETE only:
   * Remove the `NestingField` arm of the structural-rejection switch (`:500-506`) and the paired "R128's compound-entity-mutations territory" comment (`:498-499`). The `default` arm stays for genuinely-unrecognised `InputField` subtypes.
   * In the per-field admission loop, when `f` is `NestingField`, recurse on `nf.fields()` applying the same arm checks (so a buried `CompositeColumnField` on INSERT still trips the R130 deferred rejection at `:455-464`).
   * Reject `nf.list() == true` with `Rejection.structural` naming this item.
   * **No `@value` handling is needed here.** `@value` is already fully resolved on both surviving verbs: the `acceptsValueMarker()` gate (`:368-376`) hard-rejects `@value` on any top-level INSERT/DELETE field *before* the admission loop runs, and `@value` is inert on UPDATE (which never reaches this resolver). A `@value` on a *nested* INSERT/DELETE leaf is simply never read — the `valueMarkedNames` loop at `:356-383` walks only the outer input type's field definitions, not nested types — which is consistent with `@value` being meaningless on these verbs (the `else`-branch partition ignores it). The directive is retired outright by R266; R186 deliberately does not add a bespoke nested-`@value` rejection for a directive on its way out. See the [R266 interaction note](#interaction-with-neighbouring-items) for why this leaves R186 with no coupling to R266.

2. **`UpdateRowsWalker` flattening** (`UpdateRowsWalker.java:68-95`) — all UPDATE (direct-`@table`/ID-returning via `classifyUpdateTableField`, payload-returning via `classifyUpdatePayloadField`; both reach the same walker):
   * Remove the dedicated `NestingField` rejection arm (`:87-90`, `UpdateRowsError.UnsupportedInputFieldShape`). The `default` arm (`:91-94`) stays for genuinely-unsupported future permits.
   * Before the Stage 2 carrier switch, call the shared flatten helper (Representation choice) so no `NestingField` ever reaches the switch. The walker's PK-or-UK matching (`JooqCatalog.candidateKeys`, subset-of-input-covered-`sqlName`s, `:116-153`) then runs over the flattened leaves' *resolved columns* with no change: a nested leaf's column counts toward key coverage exactly as a root leaf's does, and the `SetColumn` / `KeyColumn` partition is column-identity (`sqlName`)-driven, not field-name-driven. No `@value` interaction here (R246/R258 dropped it on every UPDATE path).

3. **`TableInputArg.of` flattening** (`ArgumentRef.java:277-312`) — the INSERT/DELETE gate:
   * Before partitioning, call the same shared flatten helper on `fields`. The access path it records is the list of SDL names from input root to leaf (e.g. `["lokalisering", "landkode"]`). **Deep nesting collapses to a single carrier**: `NestedInputField`'s compact constructor forbids a `NestedInputField` leaf (`CallSiteExtraction.java:110-112`), so an `a: { b: { c } }` leaf flattens to one `NestedInputField(arg, ["a","b","c"], <leaf>)`, never a wrapped chain.
   * The partition (LookupKey vs Set) then runs over the flat-leaf list, unchanged. On the INSERT/DELETE path the partition is trivial: the live `else` branch (`:302-308`) routes every admissible `LookupKeyField` into `lookupKeyFields` and leaves `setFields` empty, *without reading `@value`*, so flattening needs only to feed it the flat leaves. **No `valueMarkedNames` change is needed.** The prior draft's `Set<String> → Set<List<String>>` widening targeted the payload-returning UPDATE `@value` partition, which R258 retired: the `kind == DmlKind.UPDATE` branch of `of` (`:291-301`) is now unreachable dead code, slated for removal by R266.
   * The flat leaf carries both its local SDL name (for the presence check against the *nested* map) and the access path (for the wire fetch), via the `NestedInputField`-rewrapped `extraction`; the emit-site refactor in step 5 reads both.

4. **`enumMapping.buildLookupBindings`**:
   * Descend into `NestingField` when constructing `InputColumnBinding` entries, so the binding records the leaf's column and the leaf's access path. `fieldBindings` then includes nested leaves under their access path, and the verb-specific WHERE/coverage checks (the DELETE PK-coverage check at `MutationInputResolver.java:523-542` and the `UpdateRowsWalker` PK-or-UK match) work unchanged: both consume column identity (`fieldBindings().targetColumns().sqlName()` / the walker's covered-`sqlName` set), not the field name.

5. **Emit-site refactor** — the shared SET/WHERE seam. Both substrates converge here: the carrier→group projections (`setGroupsOf` / `keyGroupsOf` for the `UpdateRows` carrier at `TypeFetcherGenerator.java:2100`/`:2125`, consumed by `buildCarrierUpdateChainSingle` `:3957` and `buildCarrierBulkPerRowUpdateBody` `:4333`; `setGroupsOfFields` for `TableInputArg` at `:2086`) all feed the *same* emit primitives, so the access-path threading lands once at the shared seam rather than per-substrate. The single-segment wire-access sites to generalise are `emitSetMapPuts` (`:2172`, `valueMapLocal.get($S)` / `presenceLocal.containsKey($S)` at `:2204`), `emitSetBulkCellAdds` (`:2272`, `row.get($S)`), and `appendMapBindingValueExpr` (`:2794`, `mapLocal.get($S)` at `:2808`, plus the bulk lookup helpers `emitLookupKeyDecodeLocals` `:2849` / `emitLookupKeyCellAdds` `:2877`):
   * Replace the literal `presenceLocal.containsKey($S)` / `valueMapLocal.get($S)` codegen pattern with a helper that consumes a `List<String>` access path. For a single-segment path, the emitted code is identical to today. For a multi-segment path, the emitted code chains the presence walk down the path before the leaf read; at the leaf, `containsKey` decides whether the column is written, and the value (which may be `null`) decides what it's written *to*:
     ```java
     // generated output: explicit types and meaningful names, no var / no underscore-prefixed locals
     if (in.containsKey("lokalisering")) {
         Object lokaliseringGroup = in.get("lokalisering");
         if (lokaliseringGroup instanceof Map<?, ?> lokaliseringMap) {
             if (lokaliseringMap.containsKey("landkode")) {
                 sets.put(t.LANDKODE, DSL.val(lokaliseringMap.get("landkode"), t.LANDKODE.getDataType()));
             }
         }
     }
     ```
     The presence contract honors the same absent-vs-null distinction at every nesting layer that top-level mutation inputs already honor:
     * **Key absent** at any layer → no claim about that subtree; skip every leaf under it.
     * **Key present with `null` outer value** (`lokalisering: null`) → no claim about the group; skip every leaf under it. (Reading 1 of the outer-null fork; the literal-symmetric "clear every column in the group" reading is rejected as a sharp edge with no forcing-function schema.)
     * **Key present with a Map at the outer layer** → descend; each inner leaf's absent-vs-null is honored independently per the leaf-level rule below.
     * **Leaf key absent** → skip the SET write for that column.
     * **Leaf key present with `null`** → emit `SET <col> = NULL` (explicit clear).
     * **Leaf key present with a value** → emit `SET <col> = <value>`.
     This means sending `lokalisering: { landkode: "NO", bynavn: null }` writes `landkode='NO', bynavn=NULL` and leaves `regionnavn` untouched, matching the top-level absent-vs-null semantics. Document this contract verbatim in the user docs so the difference between an absent key, a null outer, and a null leaf is visible to consumers.
   * Apply the same access-path helper to the bulk per-row cell walker (`emitSetBulkCellAdds`, where `row` is the per-row map; from there the same access-path walk produces the cell read). The SET-via-EXCLUDED upsert arm (`emitSetExcludedPuts` `:2221`) needs no change for R186 — UPSERT is refused before admission, so no nested leaf reaches it — but it shares the same presence-guard shape and would compose if R145 lands nested-UPSERT later.

6. **`InputColumnBindingGroup` and `UpdateRows` carrier-component shape**:
   * `MapGroup` today carries an SDL field name as the wire-format key. **Replace that single name with a required non-empty `List<String>` access path** whose last element is the leaf's SDL name (the top-level case is the one-element list). Prefer this over keeping the name and adding an *optional* path beside it: the optional-path shape gives two sources of truth for the wire key and lets a consumer read `.name()` and silently re-introduce the single-segment assumption this item is removing. A single required path matches `NestedInputField.path` (also non-empty, last element = leaf name), so the carrier component and the extraction agree by construction. The same shape change applies to the `UpdateRows` carrier's `SetColumn` / `KeyColumn` (today grouped on `sdlFieldName` by `setGroupsOf` / `keyGroupsOf`): they must carry the leaf's access path so the shared seam in step 5 can descend. The MutationInputResolver pre-step (R94's input-record validator surface) already recurses into nested input components via `fromMap`, so the validator side composes without change once these records know the access path.

7. **User documentation** (first-client check):
   * Section in `docs/manual/tutorial/05-mutations.adoc`: "Grouping fields with nested input types". Show the `EndreOrganisasjonInput` / `LokaliseringInput` forcing-function schema verbatim, walk the resulting mutation call (`mutation { endreOrganisasjon(input: [{ id, originalnavn, lokalisering: { landkode, bynavn } }]) }`), and call out three things: (a) the nested grouping has no DML semantics — it's purely a wire-format ergonomics shape; (b) `@field(name:)` on a nested leaf targets the outer table; (c) absent-vs-null is honored at every nesting layer — an absent outer key or a `null` outer value skips the whole group, a present outer with a Map descends and each leaf's absent-vs-null decides whether the column is written and whether the write is `NULL` or a value. Worked example: `lokalisering: { landkode: "NO", bynavn: null }` writes `landkode='NO', bynavn=NULL` and leaves `regionnavn` untouched. The chapter has no current section on nested input shapes; insertion point is right after the multi-column `@mutation(typeName: UPDATE)` example.
   * Cross-reference from `docs/manual/reference/directives/mutation.adoc`: one paragraph in the "Input shape" subsection noting that nested non-`@table` inputs are admitted as a grouping shape, with a pointer to the tutorial chapter for the worked example.
   * No changes needed to `directives/table.adoc` (the nested grouping is *not* `@table`-backed) or to the diagnostics glossary (step 1 adds no new rejection code; the list-nesting rejection reuses the existing `Rejection.structural` channel).

8. **Existing classifier invariant update**:
   * **UPDATE path (`UpdateRowsWalker` / `UpdateRows`, both direct and payload)**: R246/R258's partition invariant — `setColumns()` is the non-key-covered columns, `keyColumns()` the matched PK/UK — generalises under nesting to the *flat-projected* leaves with `NestingField` children expanded in place; the producing code holds unchanged because the walker reads the flat leaf list. Update the `UpdateRowsWalker` / `UpdateRows` javadoc accordingly, preserving the `updaterows-walker-sdl-substrate` (R257) slug pointer already in the walker javadoc.
   * **`MutationInputResolver` path (INSERT/DELETE)**: the per-verb invariant javadoc (every admissible leaf in `lookupKeyFields()`, `setFields()` empty) similarly generalises to flat-projected leaves. The "Invariant #7 (nested input)" wording that today asserts rejection (referenced by the `DML_NESTING_FIELD_DEFERRED` test label) flips to admission.
   * The access-path invariant for nested-leaf extractions (every leaf flattened out of a `NestingField` carries a `CallSiteExtraction.NestedInputField` whose access path's first segment is the immediately-enclosing `NestingField`'s SDL name) is mechanically pinned by the sealed-variant carrier on the leaf's `extraction` slot and by the pipeline-tier coverage in step 5 above.

### Interaction with neighbouring items

* **R246 (direct-UPDATE walker — Done) / R258 (payload-UPDATE walker — Done) / R266 (DELETE carrier + `@value` retirement — Backlog)**: R246 and R258 together moved *every* UPDATE shape onto `UpdateRowsWalker`, which partitions by PK-or-UK over resolved `sqlName`s with `@value` inert. R186's flattening hands the walker flat leaves and the match is column-driven, access-path-agnostic, so the partition-axis interaction is fully *resolved*. **The `@value` coupling that the Backlog draft carried is gone.** That draft kept a `valueMarkedNames` widening plus a bespoke `@value`-on-nesting rejection that R266 would later delete; post-R258 neither survives — `@value` is inert on every UPDATE path and hard-rejected at the top level on INSERT/DELETE (`acceptsValueMarker()`, `MutationInputResolver.java:368-376`) — so R186 touches none of the `@value` machinery R266 retires. **One soft interaction remains on the DELETE flatten site, and it is not a blocking dependency.** R266 moves DELETE off `MutationInputResolver` / `TableInputArg` onto a new `DeleteRows` walker carrier (mirroring R246/R258). R186 targets the substrates as they stand when it lands: if R186 lands first, DELETE-nesting flattens in `TableInputArg.of` (step 3) and R266 then carries that flatten onto the `DeleteRows` walker as part of its DELETE-onto-walker move — exactly as it already mirrors R246/R258; if R266 lands first, R186's DELETE case targets the `DeleteRows` walker (per step 2's shape) instead of `TableInputArg.of`. Either order works; the second to land does the small DELETE re-homing. INSERT-nesting and UPDATE-nesting are wholly unaffected by R266. The remaining `@condition`-axis concern is now R245's: `@condition` on mutations is half-functional (admit-but-no-emit) and R245 owns the emit wiring; a `@condition` on a `NestingField` leaf needs an access-path slot wherever R245 lands the filter carrier. Defer nested-leaf `@condition` to a follow-up and reject it at admission until R245 closes the emit path.
* **R122 (compound mutations)**: strictly disjoint. R122 admits nested *`@table`-backed* inputs that introduce additional DML targets; R186 admits nested *non-`@table`* inputs that flatten onto the outer DML target. The two arms in the BuildContext nesting branch (table-bearing vs. non-table-bearing nested input objects) stay clearly separated.
* **R189 (FK-target `@nodeId` carriers on `@mutation` inputs)**: composes. A `NestingField` leaf can carry `@nodeId(typeName: T)` and produce a `ColumnReferenceField` / `CompositeColumnReferenceField` exactly as it would at the input root. The R189 admission predicate is leaf-local; R186's flattening preserves the carrier shape, so the predicate matches transparently.
* **R171 (sealed `InputLikeType` parent)**: independent. No collision; both can land in either order.

## Tests

Tier choices reflect the project's test-pyramid guidance.

* **Pipeline tier (`GraphitronSchemaBuilderTest`)** — primary classification coverage:
  * `DML_NESTING_UPDATE_TABLE_RETURN_ADMITTED` (UpdateRowsWalker path): a direct-`@table`/ID-returning `@mutation(typeName: UPDATE)` with a nested-input grouping over columns of the outer table. Assert the resulting `MutationField.MutationUpdateTableField`'s `UpdateRows.Identified` carries the flattened nested leaves split correctly across `setColumns()` (non-key) and `keyColumns()` (matched PK/UK), and that each `SetColumn` / `KeyColumn` traces back to a leaf whose extraction is a `NestedInputField` with the right access path.
  * `DML_NESTING_PAYLOAD_RETURN_ADMITTED` (UpdateRowsWalker payload path): the R186 body's payload-returning `EndreOrganisasjonInput` shape (`LokaliseringInput { landkode, bynavn, regionnavn }` mapping to columns on a `@table(name: "organisasjon")`). Post-R258 this classifies through `classifyUpdatePayloadField` → `UpdateRowsWalker` to a `MutationBulkUpdatePayloadField` (listed input) or `MutationUpdatePayloadField` (single input); assert its `UpdateRows.Identified` carries the flattened leaves split across `setColumns()` / `keyColumns()`, each tracing to a leaf whose extraction is a `NestedInputField` with the right access path. This is the payload-substrate sibling of the direct-return case above; both exercise the walker.
  * `DML_NESTING_LIST_REJECTED`: list-typed nested input (`lokalisering: [LokaliseringInput!]`) trips `Rejection.structural` naming R186 (assert on both substrates — the rejection lives in each gate).
  * `DML_NESTING_UNRESOLVABLE_LEAF`: nested input with a leaf that doesn't resolve to an outer-table column produces `UnclassifiedType` with the existing candidate-hint message (regression assertion: the nesting branch's error path is unchanged).
  * `DML_NESTING_DEEP`: two layers deep (`a: { b: { c: String } }`) classifies the same way the one-layer case does; assert the flattened leaf carries a single `NestedInputField` with access path `["a", "b", "c"]` (not a wrapped chain — the `NestedInputField`-leaf guard would throw).
  * `DML_INSERT_NESTING_OK`, `DML_DELETE_NESTING_OK`: one case per `MutationInputResolver`-path verb (INSERT, DELETE), smaller scope (one leaf each), confirming DML-verb-coverage admission. `DML_INSERT_NESTING_OK` is the admitted successor to today's `DML_NESTING_FIELD_DEFERRED` (`GraphitronSchemaBuilderTest.java:7232`, the `Film` / `FilmInput` / `FilmTitleInput` INSERT schema): reuse that schema and flip the assertion from `UnclassifiedField` to a `MutationInsertTableField` carrying the flattened `details.title` leaf. (No UPSERT case: R246/R145 refuse UPSERT before admission; nested-UPSERT coverage rides along with R145.)
  * `DML_INSERT_NESTING_VALUE_ON_LEAF_IGNORED`: pins the one residual `@value` asymmetry. A `@value` on a *nested* INSERT (or DELETE) leaf is silently ignored — the `acceptsValueMarker()` gate (`MutationInputResolver.java:368-376`) hard-rejects `@value` only on *top-level* fields, and the `valueMarkedNames` loop never descends into nested types — whereas a top-level `@value` on the same verb is rejected. `@value` is meaningless on these verbs and R266 retires it wholesale, so R186 deliberately leaves the asymmetry rather than adding rejection logic for a dying directive; this test makes the choice visible and deletes with `@value` when R266 lands.
  * `DML_NESTING_WITH_NODEID_FK_TARGET`: a `NestingField` leaf carrying `@nodeId(typeName: T)` against an FK-target NodeType produces `ColumnReferenceField` under the nesting, with `liftedSourceColumns` on the outer table.

* **Compilation tier (`SakilaCompilationTest` or equivalent)**: one fixture schema with the forcing-function `EndreOrganisasjonInput` shape against a Sakila table (or fixtures-codegen schema if the URegOrganisasjon shape is too narrow), covering both an UPDATE (the payload-return `EndreOrganisasjonInput` shape, `UpdateRowsWalker` emit) and an INSERT or DELETE (`MutationInputResolver` / `TableInputArg` emit). Verify generated Java compiles under Java 17. Catches emit-site refactor bugs in step 5 that the classification tests can't see.

* **Execution tier (Sakila DB via `SakilaServiceTest`)**: one end-to-end execution test per classifying DML verb (INSERT, UPDATE, DELETE — no UPSERT until R145) using a small nested-input shape against an actual Sakila table, with the UPDATE case run on both the direct-`@table`-return and payload-return shapes (both `UpdateRowsWalker` emit). Confirms the access-path walk in the emitted Java produces the right SQL against a real PG, that the absent-vs-null contract from step 5 is observable on the wire (absent outer key → no group writes; outer key with `null` value → no group writes; outer key with a Map and leaf absent → no write for that column; outer key with a Map and leaf `null` → `SET <col> = NULL`; outer key with a Map and leaf value → `SET <col> = <value>`), and that key-coverage still trips when expected.

* **LSP tier (`LspNodeTypeHover` or equivalent)**: confirm hover-on-leaf inside a `NestingField` reports the outer table's column (the lookup chain that already works for top-level leaves should compose transparently — but assert it to lock in the behaviour).

## Roadmap entries

When this item completes:

* Replace `DML_NESTING_FIELD_DEFERRED` (`GraphitronSchemaBuilderTest.java:7232`) with the admitted `DML_INSERT_NESTING_OK` (same schema, assertion flipped from rejection to `MutationInsertTableField`).
* Add `changelog.md` entry capturing the landing SHA and the new admitted shape (this is the kind of milestone worth keeping in the changelog).
* Update `MutationInputResolver`'s class-level javadoc so the "Invariant #7 (nested input)" wording reflects admission rather than rejection, and `UpdateRowsWalker` / `UpdateRows` javadoc to drop the nested-input deferral.
* The "nested-input is R128's compound-entity-mutations territory" comment at `MutationInputResolver.java:498-499` deletes with that rejection arm; the parallel `UpdateRowsError.UnsupportedInputFieldShape` NestingField arm at `UpdateRowsWalker.java:87-90` deletes with it (its `default` arm at `:91-94` stays for the genuine unsupported-shape case).

## Out of scope

- Nested `@table` inputs that introduce a second DML target — that's R122's territory.
- Nested inputs whose leaves are themselves `@table`-backed shapes (R23's multi-parent territory on the output side has no symmetric input meaning yet).
- List-typed nested inputs (`lokalisering: [LokaliseringInput!]`) — rejected at both admission gates (steps 1-2 of Implementation); revisit when a forcing-function schema appears.
- UPSERT nesting — refused upstream by R246/R145 before admission; rides along with R145's UPSERT classification.
- Validation-side composition of nested input shapes with `@validator` / `@constraint`: R94's input-record shape already recurses through nested components via `fromMap`, so the validation surface composes; no new validator work needed here.
