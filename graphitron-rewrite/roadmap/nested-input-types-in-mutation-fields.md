---
id: R186
title: Nested input types in @mutation fields
status: Ready
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
created: 2026-05-20
last-updated: 2026-06-08
---

# Nested input types in @mutation fields

Two classifiers structurally reject any `InputField.NestingField` on an `@mutation` input, one per DML substrate (see [Post-R258 substrate](#post-r258-substrate) below):

* Every `@mutation(typeName: UPDATE)` now classifies through `UpdateRowsWalker`, which rejects nesting at `UpdateRowsWalker.java:87-90` (`UpdateRowsError.UnsupportedInputFieldShape`, message *"nested input types in @mutation(typeName: UPDATE) fields are not yet supported"*).
* INSERT and DELETE flow through `MutationInputResolver.resolveInput`, which rejects at `MutationInputResolver.java:500-506` (`Rejection.structural`, message *"nested input types in @mutation fields are not yet supported"*, sibling to the comment "nested-input is R128's compound-entity-mutations territory" at `:498-499`).

Neither rejection carries a plan-slug pointer, so consumers hitting this today have no roadmap item to track against. Unlike the sibling `ColumnReferenceField` / `CompositeColumnReferenceField` arms (which point at R24's join-projection work) and unlike the multi-table parent-child INSERT case (R122), a plain nested non-`@table` input that maps onto columns of the same DML target table has no roadmap coverage at all.

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

### Post-R258 substrate

This item was first drafted against an "R246-shaped substrate" in which payload-returning UPDATE still flowed through `MutationInputResolver`'s `@value` partition. **R258 (Done; changelog `ac1eee4`) moved that path.** The substrate now has two flatten-and-admit sites, partitioned by DML verb rather than by return shape:

> Re-verified against HEAD on 2026-06-08, after R259, R275 and R276 landed. R276 ("classify directiveless objects only via NestingField") reworks the *output*-side `NestingType` registration (`GraphitronSchemaBuilder.registerNestingTypes`) and leaves the *input* nesting branch this item builds on (`BuildContext.classifyInputFieldInternal`) intact; R259 refined the candidate-hint scoping inside that branch without changing its propagation shape; R275 is unrelated (source-record `@service` carriers). All line references below are current as of that check.

* **Every `@mutation(typeName: UPDATE)`** classifies through `FieldBuilder` to the `UpdateRowsWalker`, intercepted before `resolveInput` is ever called (`FieldBuilder.java:3284-3298`). The fork is on return-type identity:
  * **Direct-`@table`/ID-returning UPDATE** (R246) goes to `classifyUpdateTableField` (`FieldBuilder.java:3491`), producing `MutationField.MutationUpdateTableField`.
  * **Payload-returning UPDATE** (R258) goes to `classifyUpdatePayloadField` (`FieldBuilder.java:3615`), producing `MutationField.MutationUpdatePayloadField` (single) or `MutationField.MutationBulkUpdatePayloadField` (bulk).

  Both forks share `resolveUpdateInputArg` for the whole-arg pre-checks and both run the same `new UpdateRowsWalker().walk(fieldDef, foundTit.table(), foundTit.inputFields(), ctx.catalog)` (`FieldBuilder.java:3526` / `:3675`). The walker is a *translator over the already-classified `InputField` permits* (`UpdateRowsWalker.java:67-95`): it switches on the admitted column carriers and partitions them into the `UpdateRows.Identified(matchedKey, setColumns, keyColumns)` carrier by **PK-or-UK coverage** read from the jOOQ catalog, not by `@value`. All three UPDATE leaves carry a slim `InputArgRef` plus the `UpdateRows` carrier via the `UpdateRowsField` interface; none carry `TableInputArg`, and **no UPDATE path reads `@value`** (the precondition for R188 retiring the directive).

* **INSERT and DELETE** classify through `MutationInputResolver.resolveInput` (`MutationInputResolver.java:313-545`) to `TableInputArg`. The `@value`-driven partition (`ArgumentRef.java:289-308`) is structurally inert on these verbs: only UPDATE accepts `@value` (`DmlKind.acceptsValueMarker()` returns `this == UPDATE`), so INSERT/DELETE reject `@value` on any field (`MutationInputResolver.java:368-376`) before the partition runs. The factory's `else` arm puts every admissible carrier in `lookupKeyFields` with an empty `setFields` (`ArgumentRef.java:302-308`), and `valueMarkedNames` (`:356`, `:382`) is always empty on the Ok-returning path.

* **UPSERT** is refused outright at the top of `resolveInput` (`MutationInputResolver.java:314-320`) under the R144/R145 cardinality-safety regime; it never reaches the per-field admission loop. The retired UPDATE arm of `resolveInput` is now a loud `IllegalStateException` (`:509-521`): reaching it means a regression routed an UPDATE back onto the resolver.

So R186 spans **two flatten-and-admit sites and two rejection sites**, one per substrate. The design below treats them in parallel; they share the same upstream classification and the same `CallSiteExtraction.NestedInputField` wire-path mechanism, and differ only in where the flat leaves land (`UpdateRows` vs `TableInputArg`).

### What the classifier already gives us

The classifier already does the hard work, identically for both substrates. `BuildContext.classifyInputField` (`classifyInputFieldInternal`, BuildContext.java:1750-1845) recognises a plain (non-`@table`) input object on a `@table`-input field and recursively classifies its children against the *outer* table's `TableRef` (passed through unchanged at the recursive call, `:1822`). Each leaf returns as the same admissible carrier shape it would carry at the input root (`InputField.ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`), wrapped in an `InputField.NestingField` (`:1842-1844`) that records the SDL grouping. `@nodeId` is dispatched (`:1780`) before the nesting branch, so a `@nodeId`-carrying nested leaf produces the same reference carriers it would at the root. The classifier already rejects unresolvable leaves (`:1828-1839`, "nested input type '...' has unresolvable fields", which propagates as `UnclassifiedType` on the containing input) and circular references ("circular input type reference detected", `:1807-1809`) before admission.

The only thing standing between today's classification and a working nested `@mutation` is the two structural rejections (`UpdateRowsWalker.java:87-90`, `MutationInputResolver.java:500-506`) and the downstream emitters' assumption that every input field is reachable as a single `in.get("name")` lookup. The wire-path mechanism for nested access already exists for the query-side condition path: `CallSiteExtraction.NestedInputField(outerArgName, path, leaf)` (constructed at `FieldBuilder.java:1689`/`:1709` for `@condition` implicit body params; its own javadoc gives the `path=["where", "filmId"]` nesting example). The record self-guards against a `NestedInputField` leaf (`CallSiteExtraction.java:110-112`), so deep nesting must collapse to a single carrier with a multi-segment path, never a wrapped chain (see step 3). The mutation emitters are the only consumers that still hard-code a single-segment access.

### Resolution of the Backlog-stage open questions

* **Column-coverage** is already enforced at classification time. The leaf-side classifier returns `Unresolved` for any field whose column can't be reached on the outer table, the parent NestingField then propagates that as an `UnclassifiedType` on the containing input. No new check is needed; reuse what's there.
* **`@field(name:)` scoping**: the outer `@table` is the resolution context, period. `BuildContext.java:1822` passes `resolvedTable` through unchanged when recursing into the nested input, so `@field(name:)` and the SDL-name-defaults-to-column-name fallback both already address the outer table's columns. Document this in the user docs; no code change.
* **Nested + `@nodeId`**: admit. The leaf classifier dispatches on `@nodeId` before the nesting branch (`BuildContext.java:1780`), so `@nodeId`-carrying leaves under a `NestingField` produce the same carrier shapes (same-table → `ColumnField` / `CompositeColumnField`; FK-target → `ColumnReferenceField` / `CompositeColumnReferenceField`) as they do at the input root. The R189 admission rule (`liftedSourceColumns` on the input's own table) carries over unchanged because the outer table *is* the input's own table.
* **DML verb coverage**: admit on the three verbs that classify today (INSERT, UPDATE, DELETE). The nesting shape is purely a wire-format detail; the DML emit shape (column list, SET clause, WHERE predicate, key coverage) is over the flattened leaves, which are normal carriers. **UPDATE** (both the direct-`@table`/ID-return shape and the payload-returning shape) flattens through the single `UpdateRowsWalker` site (PK-or-UK partition over the flattened leaves' resolved columns); **INSERT and DELETE** flatten through `MutationInputResolver` / `TableInputArg`. R130's INSERT carve-out on `CompositeColumnField` stays, orthogonal axis. **UPSERT is excluded**: R144/R145 refuse `@mutation(typeName: UPSERT)` outright before the per-field admission loop, so there is no admission path to add nesting to; nested-UPSERT rides along whenever R145 lands UPSERT classification, at no extra cost here.
* **List-typed nested inputs**: reject. `nf.list() == true` (e.g. `lokalisering: [LokaliseringInput!]`) has no obvious meaning when flattening onto one outer row, and no forcing-function schema exists. Emit a structural rejection naming this item so the deferral has a redirect (the rejection lives in each gate, see steps 1 and 2).

### Representation choice

Each substrate has one gate between admission and emission: the `UpdateRowsWalker` classify loop (`UpdateRowsWalker.java:67-95`) for every UPDATE shape, and `ArgumentRef.InputTypeArg.TableInputArg.of` (`ArgumentRef.java:277-312`) for the INSERT/DELETE path. The choice below is stated for the `TableInputArg` gate; the `UpdateRowsWalker` gate takes the same shape (flatten `NestingField` to its leaf carriers before the carrier switch, rewrapping each leaf's `extraction` as `NestedInputField`), after which the walker's existing PK-or-UK partition runs over the flat leaves' resolved columns unchanged. Two viable representations:

* **A. Add `NestingField` to the `LookupKeyField` / `SetField` sealed permits and have every emitter handle it.** Honest to the SDL shape, but every emit site (`emitSetMapPuts`, `emitSetVColNameAdds`, `emitSetBulkCellAdds`, and the corresponding `lookupKey` walkers) grows a recursive arm.
* **B. Flatten in the factory / walker.** Each gate walks `NestingField` and emits its admissible-carrier leaves into the flat partition directly, rewrapping each leaf's `extraction` from its current shape into `CallSiteExtraction.NestedInputField(argName, accessPath, originalExtraction)` so the wire access knows to descend. The `LookupKeyField` / `SetField` permits stay closed against `NestingField` (they already exclude it: `InputField.java:38-48`); the partition stays purely flat-carrier-typed.

Recommend **B**, and the framing is principled rather than convenient: *SDL nesting is a wire-format shape, not a DML shape*. The DML model is flat columns on one table; the partition that drives SET / WHERE / VALUES emit is over flat carriers. `CallSiteExtraction` is the canonical home for wire-decode strategy (`NestedInputField` already lives there for the condition path), and the access path attaches to the leaf's extraction at the boundary between admission and emit, exactly where wire concerns belong. The model's flat-leaf partition is what's *true downstream*; the `NestingField` envelope persists only for consumers that legitimately need the SDL shape (validation diagnostics, LSP hovers), which is why retaining it on `TableInputArg.fields()` reads as faithful preservation rather than half-flattening.

The partition lists (the walker's `setColumns()` / `keyColumns()`; the `TableInputArg`'s `lookupKeyFields()`) carry the flat-leaf view. Consumers walk leaves only.

### Implementation sketch

1. **`UpdateRowsWalker` flattening** (`UpdateRowsWalker.java:67-95`), every UPDATE shape (direct-`@table`/ID-return and payload-return both reach this one site):
   * Remove the `NestingField` rejection arm (`:87-90`, `UpdateRowsError.UnsupportedInputFieldShape`).
   * Before (or within) the Stage-2 carrier switch, project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `CallSiteExtraction.NestedInputField` (same rewrap as the `TableInputArg` path), feeding each leaf into the existing `classifyColumnCarrier` path. The list-typed-carrier rejection in `classifyColumnCarrier` (`:182-188`) is leaf-local and already fires for a nested leaf typed as a list; add a sibling rejection for a list-typed `NestingField` (`nf.list() == true`) naming this item.
   * The walker's PK-or-UK matching (`JooqCatalog.candidateKeys`, subset-of-input-covered-columns) then runs over the flattened leaves' *resolved columns* with no change: a nested leaf's column counts toward key coverage exactly as a root leaf's does, and the `SetColumn` / `KeyColumn` partition is column-identity-driven, not field-name-driven. No `@value` interaction on this path.
   * Each emitted `Contribution` carries the leaf's local SDL name as `sdlFieldName` and the rewrapped `NestedInputField` (with the full access path) as `extraction`; the resulting `SetColumn` / `KeyColumn` (`new SetColumn(sdlFieldName, col, extraction)` / `new KeyColumn(...)`) therefore traces back to the leaf and the emitter reads the access path off `extraction`.

2. **`MutationInputResolver` admission** (`MutationInputResolver.java:442-507`), INSERT and DELETE only:
   * Remove the `NestingField` arm of the structural-rejection switch (`:500-506`) and the paired "R128's compound-entity-mutations territory" comment (`:498-499`).
   * In the per-field admission loop, when `f` is `NestingField`, recurse on `nf.fields()` applying the same arm checks (so a buried `CompositeColumnField` on INSERT still trips the R130 deferred rejection).
   * Reject `nf.list() == true` with `Rejection.structural` naming this item.
   * No `@value` handling is added. The per-SDL-field directive loop (`:357-401`) inspects the outer input type's top-level fields; a nested `@lookupKey` leaf is already rejected by the classifier (`BuildContext.java:1758-1764` returns `Unresolved`, which propagates as `UnclassifiedType`), and `@value` on a nested leaf has no partition role on INSERT/DELETE (it is inert there and R188 retires the directive outright), so R186 introduces no nested-`@value` rejection.

3. **`TableInputArg.of` flattening** (`ArgumentRef.java:277-312`), the INSERT/DELETE gate:
   * Before partitioning, walk `fields` and project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `CallSiteExtraction.NestedInputField(argName, accessPath, leafExtraction)`. The access path is the list of SDL names from input root to leaf (e.g. `["lokalisering", "landkode"]`). **Deep nesting collapses to a single carrier**: `NestedInputField`'s compact constructor forbids a `NestedInputField` leaf (`CallSiteExtraction.java:110-112`), so an `a: { b: { c } }` leaf flattens to one `NestedInputField(arg, ["a","b","c"], <leaf>)`, never a wrapped chain.
   * The partition then runs over the flat-leaf list. INSERT / DELETE put every admissible leaf into `lookupKeyFields` with an empty `setFields` (the existing `else` arm, `:302-308`). **No `valueMarkedNames` change**: it stays a `Set<String>` and is always empty on these verbs (`DmlKind.acceptsValueMarker()` admits only UPDATE, and UPDATE never reaches `TableInputArg.of`), so the partition is untouched. Retaining the `NestingField` envelope on `fields()` preserves the SDL shape for validation / LSP consumers (choice B).

4. **`enumMapping.buildLookupBindings`** (`EnumMappingResolver.java:211`), the DELETE PK-coverage check:
   * Descend into `NestingField` when constructing `InputColumnBinding` entries, so the binding records the leaf's column and the leaf's access path. `fieldBindings` then includes nested leaves under their access path, and the DELETE WHERE / PK-coverage check (`MutationInputResolver.java:523-542`, which reads `foundTia.fieldBindings()...targetColumns().sqlName()`) works unchanged because it consumes column identity, not field name. INSERT discards the binding set (`:408-410`), so this is a no-op there.

5. **Emit-site refactor**:
   * **UPDATE (all shapes)**: emit already sources SET/WHERE from the `UpdateRows` carrier, not from `@value`. `TypeFetcherGenerator.setGroupsOf(f.updateRows().setColumns())` / `keyGroupsOf(f.updateRows().keyColumns())` (`TypeFetcherGenerator.java:2083`/`:2108`) feed `emitSetMapPuts` and the WHERE emitters through the shared `buildCarrierUpdateChainSingle` / `buildCarrierBulkPerRowUpdateBody` skeletons (`:3940`/`:4300`). Since each `SetColumn` / `KeyColumn` now carries a `NestedInputField` extraction for a nested leaf, the access-path walk lands in `emitSetMapPuts` (`:2155`) and its bulk sibling.
   * **INSERT / DELETE**: the same access-path walk lands in the `tia`-sourced emit (the INSERT VALUES walker and the DELETE WHERE / lookup-key emitters) and the bulk-INSERT row walkers (`in.get(rowIdx)` becomes the row-local map; from there the same access-path walk produces the cell read).
   * Replace the literal `presenceLocal.containsKey($S)` / `valueMapLocal.get($S)` codegen pattern with a helper that consumes a `List<String>` access path. For a single-segment path, the emitted code is identical to today. For a multi-segment path, the emitted code chains the presence walk down the path before the leaf read; at the leaf, `containsKey` decides whether the column is written, and the value (which may be `null`) decides what it's written *to*:
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

6. **`InputColumnBindingGroup` shape** (`InputColumnBindingGroup.java`):
   * `InputColumnBindingGroup.MapGroup` and its `InputColumnBinding.MapBinding` entries carry an SDL field name as the wire-format key. Either extend the binding to carry an access path (default singleton) or replace the name with a path field. The MutationInputResolver pre-step (R94's input-record validator surface) already recurses into nested input components via `fromMap`, so the validator side composes without change once the binding knows the access path. (DELETE / query-side consume this; INSERT does not.)

7. **User documentation** (first-client check):
   * Section in `docs/manual/tutorial/05-mutations.adoc`: "Grouping fields with nested input types". Show the `EndreOrganisasjonInput` / `LokaliseringInput` forcing-function schema verbatim, walk the resulting mutation call (`mutation { endreOrganisasjon(input: [{ id, originalnavn, lokalisering: { landkode, bynavn } }]) }`), and call out three things: (a) the nested grouping has no DML semantics, it's purely a wire-format ergonomics shape; (b) `@field(name:)` on a nested leaf targets the outer table; (c) absent-vs-null is honored at every nesting layer (an absent outer key or a `null` outer value skips the whole group; a present outer with a Map descends and each leaf's absent-vs-null decides whether the column is written and whether the write is `NULL` or a value). Worked example: `lokalisering: { landkode: "NO", bynavn: null }` writes `landkode='NO', bynavn=NULL` and leaves `regionnavn` untouched. The chapter's "Other mutation shapes" section already shows a multi-column `@mutation(typeName: UPDATE)`; insertion point is right after it.
   * Cross-reference from `docs/manual/reference/directives/mutation.adoc`: one paragraph noting that nested non-`@table` inputs are admitted as a grouping shape, with a pointer to the tutorial chapter for the worked example. The file has no "Input shape" subsection today (its headings are "SDL signature", "Parameters", "Canonical example", "Payload-returning DELETE", "Constraints", "See also"); fold the paragraph into "Parameters" or add a new "Input shape" subsection after it.
   * No changes needed to `directives/table.adoc` (the nested grouping is *not* `@table`-backed) or to the diagnostics glossary.

8. **Existing classifier invariant update**:
   * **UPDATE path (`UpdateRowsWalker` / `UpdateRows`)**: the partition invariant, `setColumns()` is the non-key-covered columns and `keyColumns()` the matched PK/UK, generalises under nesting to the *flat-projected* leaves with `NestingField` children expanded in place; the producing code holds unchanged because the walker reads the flat leaf list. Update the `UpdateRowsWalker` / `UpdateRows` javadoc to drop the nested-input deferral and note the flatten.
   * **`MutationInputResolver` path**: the per-verb invariant javadoc (every admissible leaf in `lookupKeyFields()` on INSERT/DELETE) similarly generalises to flat-projected leaves. The "Invariant #7 (nested input)" wording that today asserts rejection (referenced by the `DML_NESTING_FIELD_DEFERRED` test label) flips to admission.
   * The access-path invariant for nested-leaf extractions (every leaf flattened out of a `NestingField` carries a `CallSiteExtraction.NestedInputField` whose access path's first segment is the immediately-enclosing `NestingField`'s SDL name) is mechanically pinned by the sealed-variant carrier on the leaf's `extraction` slot and by the pipeline-tier coverage in the tests below.

### Interaction with neighbouring items

* **R246 / R258 (UpdateRows walker, both Done)**: these are the substrate. R246 landed the direct-`@table`-return UPDATE on the walker; R258 moved the payload-returning UPDATE onto the same walker. Both partition by PK-or-UK over resolved columns, and `@value` is inert on the whole UPDATE path. So the partition-axis interaction is *resolved*: R186's flattening hands the walker flat leaves and the PK-or-UK match is column-driven, access-path-agnostic. The carrier-of-path claim (`CallSiteExtraction.NestedInputField` rewrap) is the only new structure on this path.
* **R188 (`@value` retirement)**: R258's changelog records "no UPDATE path reads `@value`, the precondition for R188 retiring the directive" and signs off "Unblocks R188". R186 carries **no `@value` interaction at all**: UPDATE never reads it (walker substrate), and INSERT/DELETE reject it wholesale before any partition. R186 and R188 are therefore independent; neither blocks the other.
* **R245 (`@condition` emit on mutations, Backlog)**: `@condition` on mutations is half-functional (admit-but-no-emit) and R245 owns the emit wiring; a `@condition` on a `NestingField` leaf needs an access-path slot wherever R245 lands the filter carrier. Defer nested-leaf `@condition` to a follow-up and reject it at admission until R245 closes the emit path (the walker's `classifyColumnCarrier` already rejects a leaf `@condition`, `UpdateRowsWalker.java:189-198`; the INSERT/DELETE path rejects via the existing per-field condition checks).
* **R266 (DELETE walker carrier, Backlog)**: when DELETE migrates onto its own walker carrier (the `deleterows-walker-carrier` item), the DELETE half of steps 2 to 4 moves onto that walker the same way UPDATE already sits on `UpdateRowsWalker`; the flatten-and-rewrap mechanism is carrier-agnostic and survives the move. R186 and R266 have no ordering dependency.
* **R122 (compound mutations)**: strictly disjoint. R122 admits nested *`@table`-backed* inputs that introduce additional DML targets; R186 admits nested *non-`@table`* inputs that flatten onto the outer DML target. The two arms in the BuildContext nesting branch (table-bearing vs. non-table-bearing nested input objects) stay clearly separated.
* **R189 (FK-target `@nodeId` carriers on `@mutation` inputs)**: composes. A `NestingField` leaf can carry `@nodeId(typeName: T)` and produce a `ColumnReferenceField` / `CompositeColumnReferenceField` exactly as it would at the input root. The R189 admission predicate is leaf-local; R186's flattening preserves the carrier shape, so the predicate matches transparently.
* **R171 (sealed `InputLikeType` parent)**: independent. No collision; both can land in either order.

## Tests

Tier choices reflect the project's test-pyramid guidance.

* **Pipeline tier (`GraphitronSchemaBuilderTest`)**, primary classification coverage:
  * `DML_NESTING_UPDATE_TABLE_RETURN_ADMITTED` (UpdateRowsWalker, direct-`@table`/ID-return): a direct-`@table`/ID-returning `@mutation(typeName: UPDATE)` with a nested-input grouping over columns of the outer table. Assert the resulting `MutationField.MutationUpdateTableField`'s `UpdateRows.Identified` carries the flattened nested leaves split correctly across `setColumns()` (non-key) and `keyColumns()` (matched PK/UK), and that each `SetColumn` / `KeyColumn` traces back to a leaf whose `extraction` is a `NestedInputField` with the right access path. Mirrors the assertion shape of `UPDATE_CARRIER_PARTITIONS_FIELDS_INTO_KEY_AND_SET` (`extracting(s -> s.sdlFieldName())`).
  * `DML_NESTING_UPDATE_PAYLOAD_RETURN_ADMITTED` (UpdateRowsWalker, payload-return): the R186 body's `EndreOrganisasjonInput` shape (`LokaliseringInput { landkode, bynavn, regionnavn }` mapping to columns on a `@table(name: "ORGANISASJON")`), bulk input + payload return. This classifies through `classifyUpdatePayloadField` to `MutationField.MutationBulkUpdatePayloadField`; assert its `updateRows()` `UpdateRows.Identified` carrier carries the flattened nested leaves across `setColumns()` / `keyColumns()` with their access paths. Confirms the payload-return UPDATE now rides the same walker flatten as the direct-return shape.
  * `DML_INSERT_NESTING_OK` (MutationInputResolver, INSERT): the case `DML_NESTING_FIELD_DEFERRED` (`GraphitronSchemaBuilderTest.java:7284`, an INSERT with `details: FilmTitleInput`) asserts today flips from rejection to admission. Assert `MutationInsertTableField`'s `TableInputArg` reaches the flattened leaves (via `fields()` / `lookupKeyFields()` or the binding set) in SDL declaration order with their `NestedInputField` access paths.
  * `DML_DELETE_NESTING_OK` (MutationInputResolver, DELETE): a small DELETE case (one nested leaf) confirming DML-verb-coverage admission and that the nested leaf's column counts toward the PK-coverage check.
  * `DML_NESTING_LIST_REJECTED`: list-typed nested input (`lokalisering: [LokaliseringInput!]`) trips `Rejection.structural` / `UpdateRowsError.UnsupportedInputFieldShape` naming R186. Assert on both substrates (an UPDATE case and an INSERT case), since the rejection lives in each gate.
  * `DML_NESTING_UNRESOLVABLE_LEAF`: nested input with a leaf that doesn't resolve to an outer-table column produces `UnclassifiedType` with the existing candidate-hint message (regression assertion: the nesting branch's error path, `BuildContext.java:1828-1839`, is unchanged). Substrate-neutral.
  * `DML_NESTING_DEEP`: two layers deep (`a: { b: { c: String } }`) classifies the same way the one-layer case does; assert the flattened leaf carries a single `NestedInputField` with access path `["a", "b", "c"]` (not a wrapped chain, which the `NestedInputField`-leaf guard at `CallSiteExtraction.java:110-112` would reject).
  * `DML_NESTING_WITH_NODEID_FK_TARGET`: a `NestingField` leaf carrying `@nodeId(typeName: T)` against an FK-target NodeType produces `ColumnReferenceField` under the nesting, with `liftedSourceColumns` on the outer table.

* **Compilation tier (`SakilaCompilationTest` or equivalent)**: one fixture schema with the forcing-function nested-input shape against a Sakila table (or fixtures-codegen schema if the URegOrganisasjon shape is too narrow), covering both a direct-`@table`-return UPDATE (UpdateRowsWalker emit), a payload-return UPDATE (UpdateRowsWalker emit), and an INSERT (MutationInputResolver emit). Verify generated Java compiles under Java 17. Catches emit-site refactor bugs in step 5 that the classification tests can't see.

* **Execution tier (Sakila DB via `SakilaServiceTest`)**: one end-to-end execution test per classifying DML verb (INSERT, UPDATE, DELETE, no UPSERT until R145) using a small nested-input shape against an actual Sakila table, with the UPDATE case run on both the direct-`@table`-return and payload-return walker paths. Confirms the access-path walk in the emitted Java produces the right SQL against a real PG, that the absent-vs-null contract from step 5 is observable on the wire (absent outer key → no group writes; outer key with `null` value → no group writes; outer key with a Map and leaf absent → no write for that column; outer key with a Map and leaf `null` → `SET <col> = NULL`; outer key with a Map and leaf value → `SET <col> = <value>`), and that key-coverage still trips when expected.

* **LSP tier (`LspNodeTypeHover` or equivalent)**: confirm hover-on-leaf inside a `NestingField` reports the outer table's column (the lookup chain that already works for top-level leaves should compose transparently, but assert it to lock in the behaviour).

## Roadmap entries

When this item completes:

* Flip `DML_NESTING_FIELD_DEFERRED` (the INSERT deferral) to the admitted `DML_INSERT_NESTING_OK` case; the deferred case becomes the admitted case.
* Add a `changelog.md` entry capturing the landing SHA and the new admitted shape (this is the kind of milestone worth keeping in the changelog).
* Update `MutationInputResolver`'s class-level javadoc so the "Invariant #7 (nested input)" wording reflects admission rather than rejection, and `UpdateRowsWalker` / `UpdateRows` javadoc to drop the nested-input deferral.
* The "nested-input is R128's compound-entity-mutations territory" comment at `MutationInputResolver.java:498-499` deletes with that rejection arm; the parallel `UpdateRowsError.UnsupportedInputFieldShape` NestingField arm at `UpdateRowsWalker.java:87-90` deletes with it (its `UpdateRowsError` arm stays for the genuine `default` unsupported-shape case).
* The `LookupKeyField` / `SetField` javadoc at `InputField.java:35-36` carries the same R128 attribution ("`NestingField` stays outside the permits set; nested-input is R128's compound-entity-mutations territory"). Its first clause stays true under choice B (the permits never admit `NestingField`; it flattens to leaves), so keep it and requalify only the attribution: a non-`@table` nested grouping is R186's territory; a `@table` nested input that introduces a second DML target remains R122's.

## Out of scope

- Nested `@table` inputs that introduce a second DML target, that's R122's territory.
- Nested inputs whose leaves are themselves `@table`-backed shapes (R23's multi-parent territory on the output side has no symmetric input meaning yet).
- List-typed nested inputs (`lokalisering: [LokaliseringInput!]`), rejected at both admission gates (steps 1 to 2 of Implementation); revisit when a forcing-function schema appears.
- UPSERT nesting, refused upstream by R144/R145 before admission; rides along with R145's UPSERT classification.
- Nested-leaf `@condition`, deferred until R245 lands the mutation `@condition` emit path; rejected at admission until then.
- Validation-side composition of nested input shapes with `@validator` / `@constraint`: R94's input-record shape already recurses through nested components via `fromMap`, so the validation surface composes; no new validator work needed here.
