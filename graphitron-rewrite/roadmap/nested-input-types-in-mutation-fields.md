---
id: R186
title: Nested input types in @mutation fields
status: Spec
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
created: 2026-05-20
last-updated: 2026-05-29
---

# Nested input types in @mutation fields

Two classifiers structurally reject any `InputField.NestingField` on an `@mutation` input. **R246 forked the UPDATE path** (see [R246-shaped substrate](#r246-shaped-substrate) below): the direct-`@table`/ID-returning UPDATE now classifies through `UpdateRowsWalker`, which rejects nesting at `UpdateRowsWalker.java:87` (`UpdateRowsError.UnsupportedInputFieldShape`, message *"nested input types in @mutation(typeName: UPDATE) fields are not yet supported"*); every other DML verb (INSERT, DELETE) and the payload-returning UPDATE still flow through `MutationInputResolver`, which rejects at `MutationInputResolver.java:501` (`Rejection.structural`, message *"nested input types in @mutation fields are not yet supported"*, sibling to the comment "nested-input is R128's compound-entity-mutations territory" at `:498-499`). Neither rejection carries a plan-slug pointer, so consumers hitting this today have no roadmap item to track against. Unlike the sibling `ColumnReferenceField` / `CompositeColumnReferenceField` arms (which point at R24's join-projection work) and unlike the multi-table parent-child INSERT case (R122), a plain nested non-`@table` input that maps onto columns of the same DML target table has no roadmap coverage at all.

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

## Relationship to neighbouring items

- **R122 (compound mutations)** covers nested inputs that introduce *additional* tables (parent + child INSERT). A non-`@table` nested input that flattens onto the outer table's columns is structurally a different shape and shouldn't have to wait for R122.
- **R24 (`NodeIdReferenceField` join projection)** covers FK-target `@nodeId` carriers, orthogonal axis.
- **R96 (input-type variant reshape)** and **R94 (input-record shape)** ship the per-input-type Java class the validator pre-step walks; the emitted class already recurses into nested input components via `fromMap`, so the validation surface composes without further work.

## Design

### R246-shaped substrate

R246 (Done; changelog `8a04c0b`/`975f593`/`c63c14c`/`f3a39ea`) landed after this item's Backlog draft and absorbed R188's UPDATE-side partition scope. It forks the `@mutation(typeName: UPDATE)` classification on **return-type identity** (`FieldBuilder.java:3170-3183`):

* **Direct-`@table`/ID-returning UPDATE** → `FieldBuilder.classifyUpdateTableField` → `UpdateRowsWalker`. The walker is a *translator over the already-classified `InputField` permits* (`UpdateRowsWalker.java:67-94`): it switches on the four admitted column carriers and partitions them into the `UpdateRows.Identified(matchedKey, setColumns, keyColumns)` carrier by **PK-or-UK coverage** read from the jOOQ catalog, not by `@value`. `MutationUpdateTableField` no longer carries `TableInputArg`; it carries a slim `InputArgRef` plus the `UpdateRows` carrier. `@value` is *ignored* on this path.
* **Payload-returning UPDATE, plus all INSERT / DELETE** → `MutationInputResolver.resolveInput` → `TableInputArg`, with the `@value`-driven `setFields()` / `lookupKeyFields()` partition (`ArgumentRef.java:289-308`) still live.
* **UPSERT** → refused outright at the top of `resolveInput` (`MutationInputResolver.java:314-319`) under the R144/R145 cardinality-safety regime; it never reaches the per-field admission loop.

So R186 now spans **two flatten-and-admit sites and two rejection sites**, one per substrate. The design below treats them in parallel; they share the same upstream classification and the same `CallSiteExtraction.NestedInputField` wire-path mechanism, and differ only in where the flat leaves land (`UpdateRows` vs `TableInputArg`).

### What the classifier already gives us

The classifier already does the hard work, identically for both paths. `BuildContext.classifyInputField` (BuildContext.java:1695-1800) recognises a plain (non-`@table`) input object on a `@table`-input field and recursively classifies its children against the *outer* table's `TableRef` (passed through unchanged at the recursive call, `:1780`). Each leaf returns as the same admissible carrier shape it would carry at the input root (`InputField.ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`), wrapped in an `InputField.NestingField` (`:1800`) that records the SDL grouping. `@nodeId` is dispatched (`:1739`) before the nesting branch, so a `@nodeId`-carrying nested leaf produces the same reference carriers it would at the root. The classifier already rejects unresolvable leaves via `UnclassifiedType` and circular references via "circular input type reference detected" (`:1767`) — both error paths land in `Unresolved` before admission.

The only thing standing between today's classification and a working nested `@mutation` is the two structural rejections (`MutationInputResolver.java:501`, `UpdateRowsWalker.java:87`) and the downstream emitters' assumption that every input field is reachable as a single `in.get("name")` lookup. The wire-path mechanism for nested access already exists for the query-side condition path: `CallSiteExtraction.NestedInputField(outerArgName, path, leaf)` (constructed at `FieldBuilder.java:1683`/`:1703` for `@condition` implicit body params; the record self-guards against a `NestedInputField` leaf, so deep nesting must collapse to a single carrier with a multi-segment path, never a wrapped chain — see step 3). The mutation emitters are the only consumers that still hard-code a single-segment access.

### Resolution of the Backlog-stage open questions

* **Column-coverage** is already enforced at classification time. The leaf-side classifier returns `Unresolved` for any field whose column can't be reached on the outer table, the parent NestingField then propagates that as an `UnclassifiedType` on the containing input. No new check is needed; reuse what's there.
* **`@field(name:)` scoping**: the outer `@table` is the resolution context, period. `BuildContext.java:1780` passes `resolvedTable` through unchanged when recursing into the nested input, so `@field(name:)` and the SDL-name-defaults-to-column-name fallback both already address the outer table's columns. Document this in the user docs; no code change.
* **Nested + `@nodeId`**: admit. The leaf classifier dispatches on `@nodeId` before the nesting branch (BuildContext.java:1739), so `@nodeId`-carrying leaves under a `NestingField` produce the same carrier shapes (same-table → `ColumnField` / `CompositeColumnField`; FK-target → `ColumnReferenceField` / `CompositeColumnReferenceField`) as they do at the input root. The R189 admission rule (`liftedSourceColumns` on the input's own table) carries over unchanged because the outer table *is* the input's own table.
* **DML verb coverage**: admit on the three verbs that classify today (INSERT, UPDATE, DELETE). The nesting shape is purely a wire-format detail; the DML emit shape (column list, SET clause, WHERE predicate, key coverage) is over the flattened leaves, which are normal carriers. R130's INSERT carve-out on `CompositeColumnField` stays — orthogonal axis. **UPSERT is excluded**: R246/R145 refuse `@mutation(typeName: UPSERT)` outright before the per-field admission loop, so there is no admission path to add nesting to; nested-UPSERT rides along whenever R145 lands UPSERT classification, at no extra cost here. **UPDATE spans both substrates**: the direct-`@table`/ID-returning UPDATE admits through `UpdateRowsWalker` (PK-or-UK partition over the flattened leaves' resolved columns); the payload-returning UPDATE and INSERT/DELETE admit through `MutationInputResolver` / `TableInputArg`.
* **List-typed nested inputs**: reject. `nf.list() == true` (e.g. `lokalisering: [LokaliseringInput!]`) has no obvious meaning when flattening onto one outer row, and no forcing-function schema exists. Emit a structural rejection naming this item so the deferral has a redirect.

### Representation choice

Each substrate has one gate between admission and emission: `ArgumentRef.InputTypeArg.TableInputArg.of` (`ArgumentRef.java:277-311`) for the `MutationInputResolver` path, and the `UpdateRowsWalker` classify loop (`UpdateRowsWalker.java:67-94`) for the direct-UPDATE path. The choice below is stated for the `TableInputArg` gate; the `UpdateRowsWalker` gate takes the same shape (flatten `NestingField` to its leaf carriers before the carrier switch, rewrapping each leaf's `extraction` as `NestedInputField`), after which the walker's existing PK-or-UK partition runs over the flat leaves' resolved columns unchanged. Two viable representations:

* **A. Add `NestingField` to the `LookupKeyField` / `SetField` sealed permits and have every emitter handle it.** Honest to the SDL shape, but every emit site (`emitSetMapPuts`, `emitSetExcludedPuts`, `emitSetVColNameAdds`, `emitSetBulkCellAdds`, `emitSetVFieldPuts`, and the corresponding `lookupKey` walkers) grows a recursive arm.
* **B. Flatten in the factory.** The factory walks `NestingField` and emits its admissible-carrier leaves into `fields` / `lookupKeyFields` / `setFields` directly, rewrapping each leaf's `extraction` from its current shape into `CallSiteExtraction.NestedInputField(argName, accessPath, originalExtraction)` so the wire access knows to descend. `LookupKeyField` / `SetField` permits stay closed against `NestingField`; the partition stays purely flat-carrier-typed.

Recommend **B**, and the framing is principled rather than convenient: *SDL nesting is a wire-format shape, not a DML shape*. The DML model is flat columns on one table; the partition that drives SET / WHERE / VALUES emit is over flat carriers. `CallSiteExtraction` is the canonical home for wire-decode strategy — `NestedInputField` already lives there for the condition path — and the access path attaches to the leaf's extraction at the boundary between admission and emit, exactly where wire concerns belong. The model's flat-leaf partition is what's *true downstream*; the `NestingField` envelope persists only for consumers that legitimately need the SDL shape (validation diagnostics, LSP hovers), which is why retaining it on `TableInputArg.fields()` reads as faithful preservation rather than half-flattening.

The partition lists (`setFields()` / `lookupKeyFields()`) carry the flat-leaf view. Consumers walk leaves only.

### Implementation sketch

1. **`MutationInputResolver` admission** (`MutationInputResolver.java:442-505`) — INSERT, DELETE, payload-returning UPDATE:
   * Remove the `NestingField` arm of the structural-rejection switch (`:501`) and the paired "R128's compound-entity-mutations territory" comment (`:498-499`).
   * In the per-field admission loop, when `f` is `NestingField`, recurse on `nf.fields()` applying the same arm checks (so a buried `CompositeColumnField` on INSERT still trips the R130 deferred rejection).
   * Reject `nf.list() == true` with `Rejection.structural` naming this item.
   * Reject `@value` on a `NestingField` carrier with a clear message (`@value applies to leaf input fields, not nested input groupings; mark the leaves under '<name>' individually`). `@value` is parsed in this resolver only on the payload-returning UPDATE path now (R246 made `@value` inert on the direct-`@table`-return UPDATE); when R188 retires `@value` outright, this rejection disappears with it.

2. **`UpdateRowsWalker` flattening** (`UpdateRowsWalker.java:67-94`) — direct-`@table`/ID-returning UPDATE:
   * Remove the `NestingField` rejection arm (`:87`, `UpdateRowsError.UnsupportedInputFieldShape`).
   * Before (or within) the carrier switch, project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `CallSiteExtraction.NestedInputField` (same rewrap as the `TableInputArg` path). The walker's PK-or-UK matching (`JooqCatalog.candidateKeys`, subset-of-input-covered-columns) then runs over the flattened leaves' *resolved columns* with no change: a nested leaf's column counts toward key coverage exactly as a root leaf's does, and the `SetColumn` / `KeyColumn` partition is column-identity-driven, not field-name-driven. No `@value` interaction here (R246 dropped it on this path).

3. **`TableInputArg.of` flattening** (`ArgumentRef.java:277-311`) — the `MutationInputResolver` path's gate:
   * Before partitioning, walk `fields` and project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `CallSiteExtraction.NestedInputField(argName, accessPath, leafExtraction)`. The access path is the list of SDL names from input root to leaf (e.g. `["lokalisering", "landkode"]`). **Deep nesting collapses to a single carrier**: `NestedInputField`'s compact constructor forbids a `NestedInputField` leaf (`CallSiteExtraction.java:109`), so an `a: { b: { c } }` leaf flattens to one `NestedInputField(arg, ["a","b","c"], <leaf>)`, never a wrapped chain.
   * The partition (LookupKey vs Set) then runs over the flat-leaf list. Only the payload-returning UPDATE path partitions by `@value`: `valueMarkedNames` (today a `Set<String>` of input-root SDL names, `MutationInputResolver.java:356`) widens to `Set<List<String>>` keyed by access path so `@value` on a leaf under a nesting field partitions correctly. Update the upstream collection (`:382`) to emit dotted-path keys. (INSERT / DELETE put every admissible leaf into `lookupKeyFields` with an empty `setFields`, so the widening is a no-op for them.)
   * `setFields().stream()...map(f -> f.name())` consumers want the leaf's local SDL name (for presence checks against the *nested* map) AND the access path (for wire fetch). Both are present on the leaf's `NestingField`-wrapped extraction; emit-site refactor in step 5 reads both.

4. **`enumMapping.buildLookupBindings`**:
   * Descend into `NestingField` when constructing `InputColumnBinding` entries, so the binding records the leaf's column and the leaf's access path. `fieldBindings` then includes nested leaves under their access path, and the verb-specific WHERE/coverage checks (`MutationInputResolver.java:507-519` and the `UpdateRowsWalker` PK-or-UK match) work unchanged: both consume column identity (`fieldBindings.targetColumns().sqlName()` / the walker's covered-column set), not the field name.

5. **Emit-site refactor** (`TypeFetcherGenerator.emitSet*` and the parallel WHERE/lookup-key emitters; for the direct-UPDATE path, R246's `buildMutationUpdateFetcher` / `buildBulkUpdateFetcher` projecting `UpdateRows.setColumns()` / `keyColumns()` back through `setGroupsOf` / `keyGroupsOf`):
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
   * Apply the same refactor to the bulk-INSERT row walkers (`in.get(rowIdx)` becomes the row-local map; from there the same access-path walk produces the cell read) and to the SET-via-EXCLUDED upsert arm.

6. **`InputColumnBindingGroup` shape**:
   * `MapGroup` today carries an SDL field name as the wire-format key. Either extend the record to carry an access path (default singleton) or replace the name with a path field. The MutationInputResolver pre-step (R94's input-record validator surface) already recurses into nested input components via `fromMap`, so the validator side composes without change once `InputColumnBindingGroup` knows the access path.

7. **User documentation** (first-client check):
   * Section in `docs/manual/tutorial/05-mutations.adoc`: "Grouping fields with nested input types". Show the `EndreOrganisasjonInput` / `LokaliseringInput` forcing-function schema verbatim, walk the resulting mutation call (`mutation { endreOrganisasjon(input: [{ id, originalnavn, lokalisering: { landkode, bynavn } }]) }`), and call out three things: (a) the nested grouping has no DML semantics — it's purely a wire-format ergonomics shape; (b) `@field(name:)` on a nested leaf targets the outer table; (c) absent-vs-null is honored at every nesting layer — an absent outer key or a `null` outer value skips the whole group, a present outer with a Map descends and each leaf's absent-vs-null decides whether the column is written and whether the write is `NULL` or a value. Worked example: `lokalisering: { landkode: "NO", bynavn: null }` writes `landkode='NO', bynavn=NULL` and leaves `regionnavn` untouched. The chapter has no current section on nested input shapes; insertion point is right after the multi-column `@mutation(typeName: UPDATE)` example.
   * Cross-reference from `docs/manual/reference/directives/mutation.adoc`: one paragraph in the "Input shape" subsection noting that nested non-`@table` inputs are admitted as a grouping shape, with a pointer to the tutorial chapter for the worked example.
   * No changes needed to `directives/table.adoc` (the nested grouping is *not* `@table`-backed) or to the diagnostics glossary unless step 1's `@value`-on-NestingField rejection ships before R188 retires `@value` — in which case the glossary gains one entry.

8. **Existing classifier invariant update**:
   * **Direct-UPDATE path (`UpdateRowsWalker` / `UpdateRows`)**: R246's partition invariant — `setColumns()` is the non-key-covered columns, `keyColumns()` the matched PK/UK — generalises under nesting to the *flat-projected* leaves with `NestingField` children expanded in place; the producing code holds unchanged because the walker reads the flat leaf list. Update the `UpdateRowsWalker` / `UpdateRows` javadoc accordingly.
   * **`MutationInputResolver` path**: the per-verb invariant javadoc (the `@value`-marked `setFields()` on payload-UPDATE; every admissible leaf in `lookupKeyFields()` on INSERT/DELETE) similarly generalises to flat-projected leaves. The "Invariant #7 (nested input)" wording that today asserts rejection (referenced by the `DML_NESTING_FIELD_DEFERRED` test label) flips to admission.
   * The access-path invariant for nested-leaf extractions (every leaf flattened out of a `NestingField` carries a `CallSiteExtraction.NestedInputField` whose access path's first segment is the immediately-enclosing `NestingField`'s SDL name) is mechanically pinned by the sealed-variant carrier on the leaf's `extraction` slot and by the pipeline-tier coverage in step 5 above.

### Interaction with neighbouring items

* **R246 (UpdateRows walker — Done) / R188 (replace `@value` with PK-default partition — Spec)**: R246 already landed R188's UPDATE-side scope: the direct-`@table`-return UPDATE partitions by PK-or-UK over resolved columns, and `@value` is inert there. So on that path the partition-axis interaction is *resolved* — R186's flattening hands the walker flat leaves and the PK-or-UK match is column-driven, access-path-agnostic. The residual `valueMarkedNames` → `Set<List<String>>` widening (step 3) touches only the `MutationInputResolver` payload-returning-UPDATE path, and only until R188 retires `@value` outright there too; the carrier-of-path claim (`CallSiteExtraction.NestedInputField` rewrap) survives R188 either way, so R186 no longer carries a meaningful ordering dependency on R188 (R246 having absorbed the part that did). The remaining `@condition`-axis concern is now R245's: `@condition` on mutations is half-functional (admit-but-no-emit) and R245 owns the emit wiring; a `@condition` on a `NestingField` leaf needs an access-path slot wherever R245 lands the filter carrier. Defer nested-leaf `@condition` to a follow-up and reject it at admission until R245 closes the emit path.
* **R122 (compound mutations)**: strictly disjoint. R122 admits nested *`@table`-backed* inputs that introduce additional DML targets; R186 admits nested *non-`@table`* inputs that flatten onto the outer DML target. The two arms in the BuildContext nesting branch (table-bearing vs. non-table-bearing nested input objects) stay clearly separated.
* **R189 (FK-target `@nodeId` carriers on `@mutation` inputs)**: composes. A `NestingField` leaf can carry `@nodeId(typeName: T)` and produce a `ColumnReferenceField` / `CompositeColumnReferenceField` exactly as it would at the input root. The R189 admission predicate is leaf-local; R186's flattening preserves the carrier shape, so the predicate matches transparently.
* **R171 (sealed `InputLikeType` parent)**: independent. No collision; both can land in either order.

## Tests

Tier choices reflect the project's test-pyramid guidance.

* **Pipeline tier (`GraphitronSchemaBuilderTest`)** — primary classification coverage:
  * `DML_NESTING_UPDATE_TABLE_RETURN_ADMITTED` (UpdateRowsWalker path): a direct-`@table`/ID-returning `@mutation(typeName: UPDATE)` with a nested-input grouping over columns of the outer table. Assert the resulting `MutationField.MutationUpdateTableField`'s `UpdateRows.Identified` carries the flattened nested leaves split correctly across `setColumns()` (non-key) and `keyColumns()` (matched PK/UK), and that each `SetColumn` / `KeyColumn` traces back to a leaf whose extraction is a `NestedInputField` with the right access path.
  * `DML_NESTING_PAYLOAD_RETURN_ADMITTED` (MutationInputResolver path): the R186 body's payload-returning `EndreOrganisasjonInput` shape (`LokaliseringInput { landkode, bynavn, regionnavn }` mapping to columns on a `@table(name: "organisasjon")`). This classifies through `resolveInput` to the bulk/record DML field; assert its `TableInputArg.setFields()` / `lookupKeyFields()` (or the binding set) contains the flattened leaves in SDL declaration order with their access paths. Replaces today's `DML_NESTING_FIELD_DEFERRED` (an INSERT case asserting the rejection), which becomes the admitted case.
  * `DML_NESTING_LIST_REJECTED`: list-typed nested input (`lokalisering: [LokaliseringInput!]`) trips `Rejection.structural` naming R186 (assert on both substrates — the rejection lives in each gate).
  * `DML_NESTING_VALUE_ON_NESTING_REJECTED`: `@value` on a `NestingField` carrier on the `MutationInputResolver` payload-returning-UPDATE path trips the per-field rejection (drop on R188 landing; not applicable to the direct-UPDATE path, where R246 already makes `@value` inert).
  * `DML_NESTING_UNRESOLVABLE_LEAF`: nested input with a leaf that doesn't resolve to an outer-table column produces `UnclassifiedType` with the existing candidate-hint message (regression assertion: the nesting branch's error path is unchanged).
  * `DML_NESTING_DEEP`: two layers deep (`a: { b: { c: String } }`) classifies the same way the one-layer case does; assert the flattened leaf carries a single `NestedInputField` with access path `["a", "b", "c"]` (not a wrapped chain — the `NestedInputField`-leaf guard would throw).
  * `DML_INSERT_NESTING_OK`, `DML_DELETE_NESTING_OK`: one case per remaining `MutationInputResolver`-path verb, smaller scope (one leaf each), to confirm DML-verb-coverage admission. (No UPSERT case: R246/R145 refuse UPSERT before admission; nested-UPSERT coverage rides along with R145.)
  * `DML_NESTING_WITH_NODEID_FK_TARGET`: a `NestingField` leaf carrying `@nodeId(typeName: T)` against an FK-target NodeType produces `ColumnReferenceField` under the nesting, with `liftedSourceColumns` on the outer table.

* **Compilation tier (`SakilaCompilationTest` or equivalent)**: one fixture schema with the forcing-function `EndreOrganisasjonInput` shape against a Sakila table (or fixtures-codegen schema if the URegOrganisasjon shape is too narrow), covering both a direct-`@table`-return UPDATE (UpdateRowsWalker emit) and a payload-return mutation (MutationInputResolver emit). Verify generated Java compiles under Java 17. Catches emit-site refactor bugs in step 5 that the classification tests can't see.

* **Execution tier (Sakila DB via `SakilaServiceTest`)**: one end-to-end execution test per classifying DML verb (INSERT, UPDATE, DELETE — no UPSERT until R145) using a small nested-input shape against an actual Sakila table, with the UPDATE case run on both the direct-`@table`-return (UpdateRowsWalker) and payload-return (MutationInputResolver) paths. Confirms the access-path walk in the emitted Java produces the right SQL against a real PG, that the absent-vs-null contract from step 5 is observable on the wire (absent outer key → no group writes; outer key with `null` value → no group writes; outer key with a Map and leaf absent → no write for that column; outer key with a Map and leaf `null` → `SET <col> = NULL`; outer key with a Map and leaf value → `SET <col> = <value>`), and that key-coverage still trips when expected.

* **LSP tier (`LspNodeTypeHover` or equivalent)**: confirm hover-on-leaf inside a `NestingField` reports the outer table's column (the lookup chain that already works for top-level leaves should compose transparently — but assert it to lock in the behaviour).

## Roadmap entries

When this item completes:

* Remove `DML_NESTING_FIELD_DEFERRED` from the pipeline-test enum; the deferred case becomes the admitted case.
* Add `changelog.md` entry capturing the landing SHA and the new admitted shape (this is the kind of milestone worth keeping in the changelog).
* Update `MutationInputResolver`'s class-level javadoc so the "Invariant #7 (nested input)" wording reflects admission rather than rejection, and `UpdateRowsWalker` / `UpdateRows` javadoc to drop the nested-input deferral.
* The "nested-input is R128's compound-entity-mutations territory" comment at `MutationInputResolver.java:498-499` deletes with that rejection arm; the parallel `UpdateRowsError.UnsupportedInputFieldShape` NestingField arm at `UpdateRowsWalker.java:87` deletes with it (and its `UpdateRowsError` arm stays for the genuine `default` unsupported-shape case).

## Out of scope

- Nested `@table` inputs that introduce a second DML target — that's R122's territory.
- Nested inputs whose leaves are themselves `@table`-backed shapes (R23's multi-parent territory on the output side has no symmetric input meaning yet).
- List-typed nested inputs (`lokalisering: [LokaliseringInput!]`) — rejected at both admission gates (steps 1-2 of Implementation); revisit when a forcing-function schema appears.
- UPSERT nesting — refused upstream by R246/R145 before admission; rides along with R145's UPSERT classification.
- Validation-side composition of nested input shapes with `@validator` / `@constraint`: R94's input-record shape already recurses through nested components via `fromMap`, so the validation surface composes; no new validator work needed here.
