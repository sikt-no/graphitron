---
id: R186
title: Nested input types in @mutation fields
status: Ready
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: [deleterows-walker-carrier]
created: 2026-05-20
last-updated: 2026-06-09
---

# Nested input types in @mutation fields

Three classifiers structurally reject any `InputField.NestingField` on an `@mutation` input, one per DML substrate (see [Post-R266 substrate](#post-r266-substrate) below):

* Every `@mutation(typeName: UPDATE)` classifies through `UpdateRowsWalker`, which rejects nesting at `UpdateRowsWalker.java:87-90` (`UpdateRowsError.UnsupportedInputFieldShape`, message *"nested input types in @mutation(typeName: UPDATE) fields are not yet supported"*).
* Every `@mutation(typeName: DELETE)` classifies through `DeleteRowsWalker` (R266), which rejects nesting at `DeleteRowsWalker.java:88-91` (`DeleteRowsError.UnsupportedInputFieldShape`, message *"nested input types in @mutation(typeName: DELETE) fields are not yet supported"*).
* INSERT flows through `MutationInputResolver.resolveInput`, which rejects at `MutationInputResolver.java:481-489` (`Rejection.structural`, message *"nested input types in @mutation fields are not yet supported"*, sibling to the comment "nested-input is R128's compound-entity-mutations territory" at `:481-482`).

None of the three rejections carries a plan-slug pointer, so consumers hitting this today have no roadmap item to track against. Unlike the sibling `ColumnReferenceField` / `CompositeColumnReferenceField` arms (which point at R24's join-projection work) and unlike the multi-table parent-child INSERT case (R122), a plain nested non-`@table` input that maps onto columns of the same DML target table has no roadmap coverage at all.

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
```

`LokaliseringInput` is a plain (non-`@table`) input that groups columns of `ORGANISASJON`. There is no second table involved; this is purely a grouping shape on the consumer side. Today this fails to classify with the `NestingField` structural rejection, even though every leaf column targets the same DML table as the outer `@table` input.

Note the substrate this example lands on: it is a bulk (`[EndreOrganisasjonInput!]!`), payload-returning (`EndreOrganisasjonPayload`) UPDATE, so under R258 it classifies through `FieldBuilder.classifyUpdatePayloadField` to `MutationBulkUpdatePayloadField` via the `UpdateRowsWalker`, never `MutationInputResolver`. The canonical R186 example is a *walker-path* case.

## Relationship to neighbouring items

- **R122 (compound mutations)** covers nested inputs that introduce *additional* tables (parent + child INSERT). A non-`@table` nested input that flattens onto the outer table's columns is structurally a different shape and shouldn't have to wait for R122.
- **R24 (`NodeIdReferenceField` join projection)** covers FK-target `@nodeId` carriers, orthogonal axis.
- **R96 (input-type variant reshape)** and **R94 (input-record shape)** ship the per-input-type Java class the validator pre-step walks; the emitted class already recurses into nested input components via `fromMap`, so the validation surface composes without further work.

## Design

### Post-R266 substrate

This item was first drafted against an "R246-shaped substrate" in which payload-returning UPDATE still flowed through `MutationInputResolver`'s `@value` partition. Two slices have moved the ground since: **R258 (Done; changelog `ac1eee4`)** routed payload-returning UPDATE onto the `UpdateRowsWalker`, and **R266 (In Review; `57cb7b0`)** routed every DELETE onto a sibling `DeleteRowsWalker` and **retired the `@value` directive and its partition machinery outright** (absorbing and discarding R188). The substrate now has three flatten-and-admit sites, partitioned by DML verb:

> Re-verified against HEAD on 2026-06-09, with R266 (`57cb7b0`) in the tree. R266 deleted `@value` / `DIR_VALUE` / `DmlKind.acceptsValueMarker()` / the `valueMarkedNames` partition loop and the `TableInputArg.of` `valueMarkedNames` parameter; the only surviving `@value` mentions under `graphitron/src/main/java` are past-tense comments documenting the retirement. The *input* nesting branch this item builds on (`BuildContext.classifyInputFieldInternal`) is intact. All line references below are current as of that check.

* **Every `@mutation(typeName: UPDATE)`** classifies through `FieldBuilder` to the `UpdateRowsWalker`, intercepted before `resolveInput` is ever called. The fork is on return-type identity: direct-`@table`/ID-returning UPDATE (R246) goes to `classifyUpdateTableField` → `MutationField.MutationUpdateTableField`; payload-returning UPDATE (R258) goes to `classifyUpdatePayloadField` → `MutationUpdatePayloadField` (single) / `MutationBulkUpdatePayloadField` (bulk). Both forks run the same `UpdateRowsWalker.walk(fieldDef, table, inputFields, catalog)`. The walker is a *translator over the already-classified `InputField` permits* (`UpdateRowsWalker.java:62-159`): it switches on the admitted column carriers (`:67-96`) and partitions them into the `UpdateRows.Identified(matchedKey, setColumns, keyColumns)` carrier by **PK-or-UK coverage** (the shared `MatchedKeys.firstCovered` matcher R266 extracted, `:114-159`), read from the jOOQ catalog. Every UPDATE leaf carries a slim `InputArgRef` plus the `UpdateRows` carrier via `UpdateRowsField`; none carry `TableInputArg`.

* **Every `@mutation(typeName: DELETE)`** classifies through `FieldBuilder` to the `DeleteRowsWalker` (R266), likewise intercepted before `resolveInput`. The walker (`DeleteRowsWalker.java:56-133`) is the same translator-over-`InputField`-permits shape as `UpdateRowsWalker` (switch at `:68-97`), but DELETE has no SET partition: *every* admitted column is a WHERE filter, so the leaves land in `DeleteRows.whereColumns()` (a `List<KeyColumn>`), and the PK-or-UK match via the shared `MatchedKeys.firstCovered` is a single-row *guard* (`DeleteRows.Identified`) rather than a partition boundary; a non-key `multiRow: true` shape lands `DeleteRows.Broadcast`. DELETE leaves carry the slim `InputArgRef` plus the `DeleteRows` carrier via `DeleteRowsField`; none carry `TableInputArg`.

* **INSERT** is now the lone verb reaching `MutationInputResolver.resolveInput` (`MutationInputResolver.java:314-508`), producing `TableInputArg`. R266 deleted the `@value` partition, so the factory unconditionally puts every admissible carrier in `lookupKeyFields` with an empty `setFields` (`ArgumentRef.java:264-282`), and INSERT walks `fields()` directly for VALUES emit (`MutationInputResolver.java:391-393` empties the binding set). The per-SDL-field directive loop (`:360-384`) rejects `@lookupKey` and non-override `@condition`; there is no `@value` marker to accumulate.

* **UPSERT** is refused outright at the top of `resolveInput` (`MutationInputResolver.java:315-321`) under the R144/R145 cardinality-safety regime; it never reaches the per-field admission loop. Both intercepted verbs share one loud guard: the UPDATE/DELETE arm of `resolveInput` is an `IllegalStateException` (`:492-505`), so a regression routing either back onto the resolver fails the build.

So R186 spans **three flatten-and-admit sites and three rejection sites**, one per substrate. The design below treats them in parallel; all three share the same upstream classification and the same `CallSiteExtraction.NestedInputField` wire-path mechanism, and differ only in where the flat leaves land (`UpdateRows.setColumns`/`keyColumns`, `DeleteRows.whereColumns`, or `TableInputArg.lookupKeyFields`). The two walkers are near-identical (R266 split `DeleteRows` from `UpdateRows` deliberately, see its Decision 6), so the UPDATE and DELETE flattening is the same edit applied twice.

### What the classifier already gives us

The classifier already does the hard work, identically for all three substrates (the nesting branch runs once, upstream of the per-verb gates). `BuildContext.classifyInputField` (`classifyInputFieldInternal`, BuildContext.java:1750-1845) recognises a plain (non-`@table`) input object on a `@table`-input field and recursively classifies its children against the *outer* table's `TableRef` (passed through unchanged at the recursive call, `:1822`). Each leaf returns as the same admissible carrier shape it would carry at the input root (`InputField.ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`), wrapped in an `InputField.NestingField` (`:1842-1844`) that records the SDL grouping. `@nodeId` is dispatched (`:1780`) before the nesting branch, so a `@nodeId`-carrying nested leaf produces the same reference carriers it would at the root. The classifier already rejects unresolvable leaves (`:1828-1839`, "nested input type '...' has unresolvable fields", which propagates as `UnclassifiedType` on the containing input) and circular references ("circular input type reference detected", `:1807-1809`) before admission.

The only thing standing between today's classification and a working nested `@mutation` is the three structural rejections (`UpdateRowsWalker.java:87-90`, `DeleteRowsWalker.java:88-91`, `MutationInputResolver.java:481-489`) and the downstream emitters' assumption that every input field is reachable as a single `in.get("name")` lookup. The wire-path mechanism for nested access already exists for the query-side condition path: `CallSiteExtraction.NestedInputField(outerArgName, path, leaf)` (constructed at `FieldBuilder.java:1689`/`:1709` for `@condition` implicit body params; its own javadoc gives the `path=["where", "filmId"]` nesting example). The record self-guards against a `NestedInputField` leaf (`CallSiteExtraction.java:110-112`), so deep nesting must collapse to a single carrier with a multi-segment path, never a wrapped chain (see step 4). The mutation emitters are the only consumers that still hard-code a single-segment access.

### Resolution of the Backlog-stage open questions

* **Column-coverage** is already enforced at classification time. The leaf-side classifier returns `Unresolved` for any field whose column can't be reached on the outer table, the parent NestingField then propagates that as an `UnclassifiedType` on the containing input. No new check is needed; reuse what's there.
* **`@field(name:)` scoping**: the outer `@table` is the resolution context, period. `BuildContext.java:1822` passes `resolvedTable` through unchanged when recursing into the nested input, so `@field(name:)` and the SDL-name-defaults-to-column-name fallback both already address the outer table's columns. Document this in the user docs; no code change.
* **Nested + `@nodeId`**: admit. The leaf classifier dispatches on `@nodeId` before the nesting branch (`BuildContext.java:1780`), so `@nodeId`-carrying leaves under a `NestingField` produce the same carrier shapes (same-table → `ColumnField` / `CompositeColumnField`; FK-target → `ColumnReferenceField` / `CompositeColumnReferenceField`) as they do at the input root. The R189 admission rule (`liftedSourceColumns` on the input's own table) carries over unchanged because the outer table *is* the input's own table.
* **DML verb coverage**: admit on the three verbs that classify today (INSERT, UPDATE, DELETE). The nesting shape is purely a wire-format detail; the DML emit shape (column list, SET clause, WHERE predicate, key coverage) is over the flattened leaves, which are normal carriers. **UPDATE** (both the direct-`@table`/ID-return shape and the payload-returning shape) flattens through the single `UpdateRowsWalker` site, **DELETE** (R266) through the sibling `DeleteRowsWalker` site, both partitioning by PK-or-UK over the flattened leaves' resolved columns; **INSERT** flattens through `MutationInputResolver` / `TableInputArg`. R130's INSERT carve-out on `CompositeColumnField` stays, orthogonal axis. **UPSERT is excluded**: R144/R145 refuse `@mutation(typeName: UPSERT)` outright before the per-field admission loop, so there is no admission path to add nesting to; nested-UPSERT rides along whenever R145 lands UPSERT classification, at no extra cost here.
* **List-typed nested inputs**: reject. `nf.list() == true` (e.g. `lokalisering: [LokaliseringInput!]`) has no obvious meaning when flattening onto one outer row, and no forcing-function schema exists. Emit a structural rejection naming this item so the deferral has a redirect (the rejection lives in each gate, see steps 1 to 3).

### Representation choice

There are now three gates between admission and emission, all the same shape: the `UpdateRowsWalker` classify loop (`UpdateRowsWalker.java:67-96`) for every UPDATE shape, the `DeleteRowsWalker` classify loop (`DeleteRowsWalker.java:68-97`) for every DELETE shape, and `ArgumentRef.InputTypeArg.TableInputArg.of` (`ArgumentRef.java:264-282`) for INSERT. The choice below is stated for the `TableInputArg` gate; both walker gates take the same shape (flatten `NestingField` to its leaf carriers before the carrier switch, rewrapping each leaf's `extraction` as `NestedInputField`), after which the walker's existing PK-or-UK partition runs over the flat leaves' resolved columns unchanged. Two viable representations:

* **A. Add `NestingField` to the `LookupKeyField` / `SetField` sealed permits and have every emitter handle it.** Honest to the SDL shape, but every emit site (`emitSetMapPuts`, `emitSetVColNameAdds`, `emitSetBulkCellAdds`, and the corresponding `lookupKey` / WHERE walkers) grows a recursive arm.
* **B. Flatten in the factory / walkers.** Each gate walks `NestingField` and emits its admissible-carrier leaves into the flat partition directly, rewrapping each leaf's `extraction` from its current shape into `CallSiteExtraction.NestedInputField(outerArgName, accessPath, originalExtraction)` so the wire access knows to descend (the walkers resolve `outerArgName` from the single `@table` argument on the `GraphQLFieldDefinition` they already receive). The `LookupKeyField` / `SetField` permits stay closed against `NestingField` (they already exclude it: `InputField.java:38-48`); the partition stays purely flat-carrier-typed.

Recommend **B**, and the framing is principled rather than convenient: *SDL nesting is a wire-format shape, not a DML shape*. The DML model is flat columns on one table; the partition that drives SET / WHERE / VALUES emit is over flat carriers. `CallSiteExtraction` is the canonical home for wire-decode strategy (`NestedInputField` already lives there for the condition path), and the access path attaches to the leaf's extraction at the boundary between admission and emit, exactly where wire concerns belong. The flat-leaf partition is what's *true downstream*; the `NestingField` envelope persists only for consumers that legitimately need the SDL shape (validation diagnostics, LSP hovers), which is why retaining it on `TableInputArg.fields()` reads as faithful preservation rather than half-flattening. B is also the R222-forward choice: it deposits flat carriers (`SetColumn` / `KeyColumn`, both already "decoupled from `InputField`" per R222 in their own javadoc) whose wire concern rides on `CallSiteExtraction`, exactly the shape R222's dimensional row carriers want; the only R186-added structure R222 later unwinds is the retained `NestingField` envelope (see [Interaction with neighbouring items](#interaction-with-neighbouring-items)).

The partition lists (the `UpdateRows` carrier's `setColumns()` / `keyColumns()`, the `DeleteRows` carrier's `whereColumns()`, the `TableInputArg`'s `lookupKeyFields()`) carry the flat-leaf view. Consumers walk leaves only.

### Implementation sketch

1. **`UpdateRowsWalker` flattening** (`UpdateRowsWalker.java:67-96`), every UPDATE shape (direct-`@table`/ID-return and payload-return both reach this one site):
   * Remove the `NestingField` rejection arm (`:87-90`, `UpdateRowsError.UnsupportedInputFieldShape`).
   * Before (or within) the Stage-2 carrier switch, project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `CallSiteExtraction.NestedInputField`, feeding each leaf into the existing `classifyColumnCarrier` path. The list-typed-carrier rejection in `classifyColumnCarrier` (`:174-179`) is leaf-local and already fires for a nested leaf typed as a list; add a sibling rejection for a list-typed `NestingField` (`nf.list() == true`) naming this item.
   * The walker's PK-or-UK matching (the shared `MatchedKeys.firstCovered`, subset-of-input-covered-columns) then runs over the flattened leaves' *resolved columns* with no change: a nested leaf's column counts toward key coverage exactly as a root leaf's does, and the `SetColumn` / `KeyColumn` partition is column-identity-driven, not field-name-driven.
   * Each emitted `Contribution` carries the leaf's local SDL name as `sdlFieldName` and the rewrapped `NestedInputField` (with the full access path) as `extraction`; the resulting `SetColumn` / `KeyColumn` (`new SetColumn(sdlFieldName, col, extraction)` / `new KeyColumn(...)`) therefore traces back to the leaf and the emitter reads the access path off `extraction`.

2. **`DeleteRowsWalker` flattening** (`DeleteRowsWalker.java:68-97`), every DELETE shape, **the same edit as step 1 applied to the sibling walker** (R266 split `DeleteRows` from `UpdateRows`, so the two walkers are near-identical):
   * Remove the `NestingField` rejection arm (`:88-91`, `DeleteRowsError.UnsupportedInputFieldShape`).
   * Project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `NestedInputField`, feeding each leaf into the same `classifyColumnCarrier` (`:143-167`); add the list-typed-`NestingField` rejection (sibling to the leaf-local list rejection at `:148-154`) as a `DeleteRowsError.UnsupportedInputFieldShape` naming this item.
   * The Stage-3 `whereColumns` build (`:104-116`) and the shared `MatchedKeys.firstCovered` match run over the flattened leaves' resolved columns unchanged: every admitted leaf's column becomes a `KeyColumn` WHERE filter, and a nested leaf's column counts toward the single-row PK-or-UK guard exactly as a root leaf's does. `DeleteRows.Identified` / `DeleteRows.Broadcast` are produced identically; each `KeyColumn` traces back to the leaf via its rewrapped `extraction`.

3. **`MutationInputResolver` admission** (`MutationInputResolver.java:425-490`), **INSERT only** (UPDATE and DELETE are intercepted upstream by their walker classifiers; UPSERT is refused at `:315-321`):
   * Remove the `NestingField` arm of the structural-rejection switch (`:481-489`) and the paired "R128's compound-entity-mutations territory" comment (`:481-482`).
   * In the per-field admission loop, when `f` is `NestingField`, recurse on `nf.fields()` applying the same arm checks (so a buried `CompositeColumnField` on INSERT still trips the R130 deferred rejection at `:437-448`).
   * Reject `nf.list() == true` with `Rejection.structural` naming this item.
   * No `@value` handling: R266 deleted the directive and its machinery, so the per-SDL-field directive loop (`:360-384`) no longer inspects `@value`. A nested `@lookupKey` leaf is already rejected by the classifier (`BuildContext.classifyInputFieldInternal` returns `Unresolved`, which propagates as `UnclassifiedType`), so R186 introduces no nested-directive rejection here.

4. **`TableInputArg.of` flattening** (`ArgumentRef.java:264-282`), the INSERT gate (and the query-side composite-key lookup, which shares this factory):
   * Before partitioning, walk `fields` and project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `CallSiteExtraction.NestedInputField(outerArgName, accessPath, leafExtraction)`. The access path is the list of SDL names from input root to leaf (e.g. `["lokalisering", "landkode"]`). **Deep nesting collapses to a single carrier**: `NestedInputField`'s compact constructor forbids a `NestedInputField` leaf (`CallSiteExtraction.java:110-112`), so an `a: { b: { c } }` leaf flattens to one `NestedInputField(arg, ["a","b","c"], <leaf>)`, never a wrapped chain.
   * The factory's unconditional partition (`:274-278`) then runs over the flat-leaf list, putting every admissible leaf into `lookupKeyFields` with an empty `setFields`. R266 removed the `valueMarkedNames` parameter and the `@value`-driven partition entirely, so there is no per-verb branch and nothing to thread; the flatten simply feeds more leaves into the one surviving arm. Retaining the `NestingField` envelope on `fields()` preserves the SDL shape for validation / LSP consumers (choice B); the INSERT VALUES emit consumes the flat-leaf partition, not the envelope (step 5).

   *(The earlier draft carried two further steps, an `EnumMappingResolver.buildLookupBindings` descent and an `InputColumnBindingGroup` access-path field, both for the DELETE PK-coverage check. R266 made them moot for mutations: DELETE now sources its WHERE from `DeleteRows.whereColumns()` via the walker, and INSERT discards the binding set (`MutationInputResolver.java:391-393`), so neither mutation verb consumes `InputColumnBindingGroup` anymore. The binding-group access-path work, if ever needed, is query-side only and out of R186's scope.)*

5. **Emit-site refactor** (three carrier-sourced paths, one shared access-path helper):
   * **UPDATE (all shapes)**: emit already sources SET/WHERE from the `UpdateRows` carrier. `TypeFetcherGenerator.setGroupsOf(f.updateRows().setColumns())` / `keyGroupsOf(f.updateRows().keyColumns())` feed `emitSetMapPuts` and the WHERE emitters. Since each `SetColumn` / `KeyColumn` now carries a `NestedInputField` extraction for a nested leaf, the access-path walk lands in `emitSetMapPuts` and its bulk sibling.
   * **DELETE (all shapes, R266)**: emit sources WHERE from the `DeleteRows` carrier. `buildRecordDeleteChain` / `buildBulkRecordPerRowDeleteBody` read `deleteRows().whereColumns()` and feed the shared `buildLookupWhereSingleRow` / `buildBulkLookupRowIn` WHERE emitters; the access-path walk lands there. There is no `tia`-sourced path left for DELETE.
   * **INSERT**: the access-path walk lands in the `tia`-sourced VALUES emit, consuming the flat-leaf partition (`lookupKeyFields`) rather than the envelope-bearing `fields()`; the bulk-INSERT row walkers descend `in.get(rowIdx)` to the row-local map and run the same access-path walk for each cell read.
   * Replace the literal `presenceLocal.containsKey($S)` / `valueMapLocal.get($S)` codegen pattern with a helper that consumes a `List<String>` access path. For a single-segment path, the emitted code is identical to today. For a multi-segment path, the emitted code chains the presence walk down the path before the leaf read; at the leaf, `containsKey` decides whether the column is written, and the value (which may be `null`) decides what it's written *to* (shown for an UPDATE SET; the DELETE WHERE and INSERT VALUES walks honor the analogous absent-vs-null):
     ```java
     if (in.containsKey("lokalisering")) {
         var __outer = in.get("lokalisering");
         if (__outer instanceof Map<?,?> __m) {
             if (__m.containsKey("landkode")) {
                 sets.put(t.LANDKODE, DSL.val(__m.get("landkode"), t.LANDKODE.getDataType()));
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

6. **User documentation** (first-client check):
   * Section in `docs/manual/tutorial/05-mutations.adoc`: "Grouping fields with nested input types". Show the `EndreOrganisasjonInput` / `LokaliseringInput` forcing-function schema verbatim, walk the resulting mutation call (`mutation { endreOrganisasjon(input: [{ id, originalnavn, lokalisering: { landkode, bynavn } }]) }`), and call out three things: (a) the nested grouping has no DML semantics, it's purely a wire-format ergonomics shape; (b) `@field(name:)` on a nested leaf targets the outer table; (c) absent-vs-null is honored at every nesting layer (an absent outer key or a `null` outer value skips the whole group; a present outer with a Map descends and each leaf's absent-vs-null decides whether the column is written and whether the write is `NULL` or a value). Worked example: `lokalisering: { landkode: "NO", bynavn: null }` writes `landkode='NO', bynavn=NULL` and leaves `regionnavn` untouched. The chapter's "Other mutation shapes" section already shows a multi-column `@mutation(typeName: UPDATE)`; insertion point is right after it.
   * Cross-reference from `docs/manual/reference/directives/mutation.adoc`: one paragraph noting that nested non-`@table` inputs are admitted as a grouping shape, with a pointer to the tutorial chapter for the worked example. The file has no "Input shape" subsection today (its headings are "SDL signature", "Parameters", "Canonical example", "Payload-returning DELETE", "Constraints", "See also"); fold the paragraph into "Parameters" or add a new "Input shape" subsection after it.
   * No changes needed to `directives/table.adoc` (the nested grouping is *not* `@table`-backed) or to the diagnostics glossary.

7. **Existing classifier invariant update**:
   * **UPDATE / DELETE walker paths (`UpdateRowsWalker` / `UpdateRows`, `DeleteRowsWalker` / `DeleteRows`)**: the partition invariants (`setColumns()` non-key-covered + `keyColumns()` matched PK/UK on UPDATE; `whereColumns()` every admitted column on DELETE) generalise under nesting to the *flat-projected* leaves with `NestingField` children expanded in place; the producing code holds unchanged because each walker reads the flat leaf list. Update the `UpdateRowsWalker` / `UpdateRows` and `DeleteRowsWalker` / `DeleteRows` javadoc to drop the nested-input deferral and note the flatten.
   * **`MutationInputResolver` path (INSERT only)**: the per-verb invariant javadoc (every admissible leaf in `lookupKeyFields()` on INSERT) generalises to flat-projected leaves. The "Invariant #7 (nested input)" wording that today asserts rejection (referenced by the `DML_NESTING_FIELD_DEFERRED` test label) flips to admission.
   * The access-path invariant for nested-leaf extractions (every leaf flattened out of a `NestingField` carries a `CallSiteExtraction.NestedInputField` whose access path's first segment is the immediately-enclosing `NestingField`'s SDL name) is mechanically pinned by the sealed-variant carrier on the leaf's `extraction` slot and by the pipeline-tier coverage in the tests below.

### Interaction with neighbouring items

* **R246 / R258 / R266 (the walker substrate)**: these are the substrate. R246 (Done) landed the direct-`@table`-return UPDATE on `UpdateRowsWalker`; R258 (Done) moved the payload-returning UPDATE onto the same walker; R266 (In Review) landed the sibling `DeleteRowsWalker` for DELETE and retired `@value` outright. All partition by PK-or-UK over resolved columns. So the partition-axis interaction is *resolved* on all three verbs: R186's flattening hands each walker flat leaves and the PK-or-UK match is column-driven, access-path-agnostic. The carrier-of-path claim (`CallSiteExtraction.NestedInputField` rewrap) is the only new structure on these paths.
* **R188 (`@value` retirement, absorbed by R266)**: R266 absorbed and discarded R188, deleting the `@value` directive and its partition machinery outright as DELETE left the resolver. R186 carries **no `@value` interaction at all**: by the time R186 lands on the post-R266 substrate the directive no longer exists, so there is nothing to read, reject, or thread.
* **R245 (`@condition` emit on mutations, Backlog)**: `@condition` on mutations is half-functional (admit-but-no-emit) and R245 owns the emit wiring; a `@condition` on a `NestingField` leaf needs an access-path slot wherever R245 lands the filter carrier. Defer nested-leaf `@condition` to a follow-up and reject it at admission until R245 closes the emit path (both walkers' `classifyColumnCarrier` already reject a leaf `@condition`, `UpdateRowsWalker.java:181-190` / `DeleteRowsWalker.java:155-165`; the INSERT path rejects via the existing per-field condition checks in `MutationInputResolver`).
* **R266 (DELETE walker carrier, In Review, `57cb7b0`)**: **landed in-tree; this spec is rebased onto it.** R266 moved DELETE off `MutationInputResolver` onto the sibling `DeleteRowsWalker` and retired `@value`, so the DELETE half of this design now mirrors the UPDATE half exactly (step 2 mirrors step 1; the two binding-path steps the earlier draft carried for DELETE PK-coverage dropped, see the note after step 4). The flatten-and-rewrap mechanism is carrier-agnostic, so the *design* has no hard dependency on R266 (it would work on either substrate); but because R266 reshapes the DELETE gate and is landing first, implementing R186's pre-R266 DELETE path would write code R266 has already torn out, and both touch the same `MutationInputResolver` lines. Hence the `depends-on: [deleterows-walker-carrier]` edge: sequence R186 after R266 is Done. The edge is recorded for sequencing, not because the carrier shape constrains the design.
* **R122 (compound mutations)**: strictly disjoint. R122 admits nested *`@table`-backed* inputs that introduce additional DML targets; R186 admits nested *non-`@table`* inputs that flatten onto the outer DML target. The two arms in the BuildContext nesting branch (table-bearing vs. non-table-bearing nested input objects) stay clearly separated.
* **R189 (FK-target `@nodeId` carriers on `@mutation` inputs)**: composes. A `NestingField` leaf can carry `@nodeId(typeName: T)` and produce a `ColumnReferenceField` / `CompositeColumnReferenceField` exactly as it would at the input root. The R189 admission predicate is leaf-local; R186's flattening preserves the carrier shape, so the predicate matches transparently.
* **R171 (sealed `InputLikeType` parent)**: independent. No collision; both can land in either order.
* **R222 (dimensional model pivot, Spec)**: forward-compatible; R186 is a small down-payment on R222's shape, not a debt against it. The carriers R186 fills (`UpdateRows`, `DeleteRows`, and `TableInputArg`'s `lookupKeyFields`) are the row-shaping carriers R222 names as Stage-2 walker-carrier slots, and the flat `SetColumn` / `KeyColumn` leaves R186 deposits are already "decoupled from `InputField`" per R222 (their own javadoc), with the wire concern parked on `CallSiteExtraction` (R222's canonical wire-decode home). The one R186-added structure R222 later unwinds is the retained `NestingField` envelope on `TableInputArg.fields()`: R222 Stage 5 retires the whole `InputField` family (`NestingField` included), and the envelope's two consumer jobs migrate cleanly, validation onto R222's `ValidationShape` carrier (which already reads graphql-java's input object directly via R94's `fromMap` recursion, never the model node) and LSP-hover onto the leaf's `NestedInputField` access path (the SDL grouping tree is recoverable from the leaves by common-prefix grouping). So the flat-leaf-plus-access-path output survives the pivot intact; only the transitional envelope is R222's to delete. No ordering dependency either way.

## Tests

Tier choices reflect the project's test-pyramid guidance.

* **Pipeline tier (`GraphitronSchemaBuilderTest`)**, primary classification coverage:
  * `DML_NESTING_UPDATE_TABLE_RETURN_ADMITTED` (UpdateRowsWalker, direct-`@table`/ID-return): a direct-`@table`/ID-returning `@mutation(typeName: UPDATE)` with a nested-input grouping over columns of the outer table. Assert the resulting `MutationField.MutationUpdateTableField`'s `UpdateRows.Identified` carries the flattened nested leaves split correctly across `setColumns()` (non-key) and `keyColumns()` (matched PK/UK), and that each `SetColumn` / `KeyColumn` traces back to a leaf whose `extraction` is a `NestedInputField` with the right access path. Mirrors the assertion shape of `UPDATE_CARRIER_PARTITIONS_FIELDS_INTO_KEY_AND_SET` (`extracting(s -> s.sdlFieldName())`).
  * `DML_NESTING_UPDATE_PAYLOAD_RETURN_ADMITTED` (UpdateRowsWalker, payload-return): the R186 body's `EndreOrganisasjonInput` shape (`LokaliseringInput { landkode, bynavn, regionnavn }` mapping to columns on a `@table(name: "ORGANISASJON")`), bulk input + payload return. This classifies through `classifyUpdatePayloadField` to `MutationField.MutationBulkUpdatePayloadField`; assert its `updateRows()` `UpdateRows.Identified` carrier carries the flattened nested leaves across `setColumns()` / `keyColumns()` with their access paths. Confirms the payload-return UPDATE now rides the same walker flatten as the direct-return shape.
  * `DML_INSERT_NESTING_OK` (MutationInputResolver, INSERT): the case `DML_NESTING_FIELD_DEFERRED` (`GraphitronSchemaBuilderTest.java:7284`, an INSERT with `details: FilmTitleInput`) asserts today flips from rejection to admission. Assert `MutationInsertTableField`'s `TableInputArg` reaches the flattened leaves via `lookupKeyFields()` (the flat-leaf partition; `fields()` retains the `NestingField` envelope) in SDL declaration order with their `NestedInputField` access paths.
  * `DML_DELETE_NESTING_OK` (DeleteRowsWalker, DELETE): a small DELETE case (one nested leaf) confirming DML-verb-coverage admission; assert the resulting `DeleteRows` carrier's `whereColumns()` includes the flattened nested leaf with its `NestedInputField` access path, and that the leaf's column counts toward the single-row PK-or-UK guard (`DeleteRows.Identified`).
  * `DML_NESTING_LIST_REJECTED`: list-typed nested input (`lokalisering: [LokaliseringInput!]`) trips the list-typed-`NestingField` rejection naming R186. Assert on all three substrates: an UPDATE case (`UpdateRowsError.UnsupportedInputFieldShape`), a DELETE case (`DeleteRowsError.UnsupportedInputFieldShape`), and an INSERT case (`Rejection.structural`), since the rejection lives in each gate.
  * `DML_NESTING_UNRESOLVABLE_LEAF`: nested input with a leaf that doesn't resolve to an outer-table column produces `UnclassifiedType` with the existing candidate-hint message (regression assertion: the nesting branch's error path, `BuildContext.java:1828-1839`, is unchanged). Substrate-neutral.
  * `DML_NESTING_DEEP`: two layers deep (`a: { b: { c: String } }`) classifies the same way the one-layer case does; assert the flattened leaf carries a single `NestedInputField` with access path `["a", "b", "c"]` (not a wrapped chain, which the `NestedInputField`-leaf guard at `CallSiteExtraction.java:110-112` would reject).
  * `DML_NESTING_WITH_NODEID_FK_TARGET`: a `NestingField` leaf carrying `@nodeId(typeName: T)` against an FK-target NodeType produces `ColumnReferenceField` under the nesting, with `liftedSourceColumns` on the outer table.

* **Compilation tier (`SakilaCompilationTest` or equivalent)**: one fixture schema with the forcing-function nested-input shape against a Sakila table (or fixtures-codegen schema if the URegOrganisasjon shape is too narrow), covering a direct-`@table`-return UPDATE (UpdateRowsWalker emit), a payload-return UPDATE (UpdateRowsWalker emit), a DELETE (DeleteRowsWalker emit), and an INSERT (MutationInputResolver emit). Verify generated Java compiles under Java 17. Catches emit-site refactor bugs in step 5 that the classification tests can't see.

* **Execution tier (Sakila DB via `SakilaServiceTest`)**: one end-to-end execution test per classifying DML verb (INSERT, UPDATE, DELETE, no UPSERT until R145) using a small nested-input shape against an actual Sakila table, with the UPDATE case run on both the direct-`@table`-return and payload-return walker paths. Confirms the access-path walk in the emitted Java produces the right SQL against a real PG, that the absent-vs-null contract from step 5 is observable on the wire (absent outer key → no group writes; outer key with `null` value → no group writes; outer key with a Map and leaf absent → no write for that column; outer key with a Map and leaf `null` → `SET <col> = NULL`; outer key with a Map and leaf value → `SET <col> = <value>`), and that key-coverage still trips when expected.

* **LSP tier (`LspNodeTypeHover` or equivalent)**: confirm hover-on-leaf inside a `NestingField` reports the outer table's column (the lookup chain that already works for top-level leaves should compose transparently, but assert it to lock in the behaviour).

## Roadmap entries

When this item completes:

* Flip `DML_NESTING_FIELD_DEFERRED` (the INSERT deferral) to the admitted `DML_INSERT_NESTING_OK` case; the deferred case becomes the admitted case.
* Add a `changelog.md` entry capturing the landing SHA and the new admitted shape (this is the kind of milestone worth keeping in the changelog).
* Update `MutationInputResolver`'s class-level javadoc so the "Invariant #7 (nested input)" wording reflects admission rather than rejection (INSERT only now), and the `UpdateRowsWalker` / `UpdateRows` and `DeleteRowsWalker` / `DeleteRows` javadoc to drop the nested-input deferral.
* The "nested-input is R128's compound-entity-mutations territory" comment at `MutationInputResolver.java:481-482` deletes with that rejection arm; the parallel `NestingField` rejection arms in both walkers (`UpdateRowsWalker.java:87-90`, `DeleteRowsWalker.java:88-91`) delete with it (each walker's `default` unsupported-shape arm stays for genuine unsupported carriers).
* The `LookupKeyField` / `SetField` javadoc at `InputField.java:35-36` carries the same R128 attribution ("`NestingField` stays outside the permits set; nested-input is R128's compound-entity-mutations territory"). Its first clause stays true under choice B (the permits never admit `NestingField`; it flattens to leaves), so keep it and requalify only the attribution: a non-`@table` nested grouping is R186's territory; a `@table` nested input that introduces a second DML target remains R122's.

## Out of scope

- Nested `@table` inputs that introduce a second DML target, that's R122's territory.
- Nested inputs whose leaves are themselves `@table`-backed shapes (R23's multi-parent territory on the output side has no symmetric input meaning yet).
- List-typed nested inputs (`lokalisering: [LokaliseringInput!]`), rejected at all three admission gates (steps 1 to 3 of Implementation); revisit when a forcing-function schema appears.
- UPSERT nesting, refused upstream by R144/R145 before admission; rides along with R145's UPSERT classification.
- Nested-leaf `@condition`, deferred until R245 lands the mutation `@condition` emit path; rejected at admission until then.
- Validation-side composition of nested input shapes with `@validator` / `@constraint`: R94's input-record shape already recurses through nested components via `fromMap`, so the validation surface composes; no new validator work needed here.
