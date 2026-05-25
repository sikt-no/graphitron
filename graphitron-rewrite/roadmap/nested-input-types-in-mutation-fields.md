---
id: R186
title: Nested input types in @mutation fields
status: Spec
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Nested input types in @mutation fields

`MutationInputResolver` structurally rejects any `InputField.NestingField` on an `@mutation` input with the message *"nested input types in @mutation fields are not yet supported"* (`MutationInputResolver.java:464`). The rejection is `Rejection.structural` with no plan-slug pointer, so consumers hitting this today have no roadmap item to track against. Unlike the sibling `ColumnReferenceField` / `CompositeColumnReferenceField` arms in the same switch (which point at R24's join-projection work) and unlike the multi-table parent-child INSERT case (R122), a plain nested non-`@table` input that maps onto columns of the same DML target table has no roadmap coverage at all.

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

The classifier already does the hard work. `BuildContext.classifyInputField` (BuildContext.java:1659-1695) recognises a plain (non-`@table`) input object on a `@table`-input field and recursively classifies its children against the *outer* table's `TableRef`. Each leaf returns as the same admissible carrier shape it would carry at the input root (`InputField.ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`), wrapped in an `InputField.NestingField` that records the SDL grouping. The classifier already rejects unresolvable leaves via `UnclassifiedType` and circular references via "circular input type reference detected" — both error paths land in `Unresolved` before admission.

The only thing standing between today's classification and a working nested `@mutation` is the structural rejection at `MutationInputResolver.java:480` and the downstream emitters' assumption that every input field is reachable as a single `in.get("name")` lookup. The wire-path mechanism for nested access already exists for the query-side condition path: `CallSiteExtraction.NestedInputField(outerArgName, leafPath, leaf)` (used at `FieldBuilder.java:1603` for `@condition` implicit body params). The mutation emitters are the only consumers that still hard-code a single-segment access.

### Resolution of the Backlog-stage open questions

* **Column-coverage** is already enforced at classification time. The leaf-side classifier returns `Unresolved` for any field whose column can't be reached on the outer table, the parent NestingField then propagates that as an `UnclassifiedType` on the containing input. No new check is needed; reuse what's there.
* **`@field(name:)` scoping**: the outer `@table` is the resolution context, period. `BuildContext.java:1672` passes `resolvedTable` through unchanged when recursing into the nested input, so `@field(name:)` and the SDL-name-defaults-to-column-name fallback both already address the outer table's columns. Document this in the user docs; no code change.
* **Nested + `@nodeId`**: admit. The leaf classifier dispatches on `@nodeId` before the nesting branch (BuildContext.java:1634), so `@nodeId`-carrying leaves under a `NestingField` produce the same carrier shapes (same-table → `ColumnField` / `CompositeColumnField`; FK-target → `ColumnReferenceField` / `CompositeColumnReferenceField`) as they do at the input root. The R189 admission rule (`liftedSourceColumns` on the input's own table) carries over unchanged because the outer table *is* the input's own table.
* **DML verb coverage**: admit on all four (INSERT, UPDATE, UPSERT, DELETE). The nesting shape is purely a wire-format detail; the DML emit shape (column list, SET clause, WHERE predicate, PK coverage) is over the flattened leaves, which are normal carriers. R130's INSERT carve-out on `CompositeColumnField` stays — orthogonal axis.
* **List-typed nested inputs**: reject. `nf.list() == true` (e.g. `lokalisering: [LokaliseringInput!]`) has no obvious meaning when flattening onto one outer row, and no forcing-function schema exists. Emit a structural rejection naming this item so the deferral has a redirect.

### Representation choice

The factory `ArgumentRef.InputTypeArg.TableInputArg.of` (ArgumentRef.java:277-313) is the gate between admission and emission. Two viable representations:

* **A. Add `NestingField` to the `LookupKeyField` / `SetField` sealed permits and have every emitter handle it.** Honest to the SDL shape, but every emit site (`emitSetMapPuts`, `emitSetExcludedPuts`, `emitSetVColNameAdds`, `emitSetBulkCellAdds`, `emitSetVFieldPuts`, and the corresponding `lookupKey` walkers) grows a recursive arm.
* **B. Flatten in the factory.** The factory walks `NestingField` and emits its admissible-carrier leaves into `fields` / `lookupKeyFields` / `setFields` directly, rewrapping each leaf's `extraction` from its current shape into `CallSiteExtraction.NestedInputField(argName, accessPath, originalExtraction)` so the wire access knows to descend. `LookupKeyField` / `SetField` permits stay closed against `NestingField`; the partition stays purely flat-carrier-typed.

Recommend **B**, and the framing is principled rather than convenient: *SDL nesting is a wire-format shape, not a DML shape*. The DML model is flat columns on one table; the partition that drives SET / WHERE / VALUES emit is over flat carriers. `CallSiteExtraction` is the canonical home for wire-decode strategy — `NestedInputField` already lives there for the condition path — and the access path attaches to the leaf's extraction at the boundary between admission and emit, exactly where wire concerns belong. The model's flat-leaf partition is what's *true downstream*; the `NestingField` envelope persists only for consumers that legitimately need the SDL shape (validation diagnostics, LSP hovers), which is why retaining it on `TableInputArg.fields()` reads as faithful preservation rather than half-flattening.

The partition lists (`setFields()` / `lookupKeyFields()`) carry the flat-leaf view. Consumers walk leaves only.

### Implementation sketch

1. **`MutationInputResolver` admission** (`MutationInputResolver.java:442-486`):
   * Remove the `NestingField` arm of the structural-rejection switch.
   * In the per-field admission loop, when `f` is `NestingField`, recurse on `nf.fields()` applying the same arm checks (so a buried `CompositeColumnField` on INSERT still trips the R130 deferred rejection).
   * Reject `nf.list() == true` with `Rejection.structural` naming this item.
   * Reject `@value` on a `NestingField` carrier with a clear message (`@value applies to leaf input fields, not nested input groupings; mark the leaves under '<name>' individually`). When R188 lands and `@value` disappears, this rejection disappears with it.

2. **`TableInputArg.of` flattening** (`ArgumentRef.java:277-313`):
   * Before partitioning, walk `fields` and project each `NestingField` to its admissible-carrier leaves with `extraction` rewrapped into `CallSiteExtraction.NestedInputField(argName, accessPath, leafExtraction)`. The access path is the list of SDL names from input root to leaf (e.g. `["lokalisering", "landkode"]`).
   * The partition (LookupKey vs Set) then runs over the flat-leaf list. `valueMarkedNames` (today a `Set<String>` of input-root SDL names) widens to `Set<List<String>>` keyed by access path so `@value` on a leaf under a nesting field partitions correctly. Update the upstream collection in `MutationInputResolver` to emit dotted-path keys.
   * `setFields().stream()...map(f -> f.name())` consumers want the leaf's local SDL name (for presence checks against the *nested* map) AND the access path (for wire fetch). Both are present on the leaf's `NestingField`-wrapped extraction; emit-site refactor in step 4 reads both.

3. **`LookupMappingResolver.buildLookupBindings`**:
   * Descend into `NestingField` when constructing `InputColumnBinding` entries, so the binding records the leaf's column and the leaf's access path. `fieldBindings` then includes nested leaves under their access path, and the PK-coverage check at `MutationInputResolver.java:501-519` works unchanged (it consumes `fieldBindings.targetColumns().sqlName()` — the column identity, not the field name).

4. **Emit-site refactor** (`TypeFetcherGenerator.emitSet*` and the parallel WHERE/lookup-key emitters):
   * Replace the literal `presenceLocal.containsKey($S)` / `valueMapLocal.get($S)` codegen pattern with a helper that consumes a `List<String>` access path. For a single-segment path, the emitted code is identical to today. For a multi-segment path, the emitted code chains presence + non-null `get` checks down the path before the leaf read:
     ```java
     if (in.containsKey("lokalisering")) {
         var __nested = in.get("lokalisering");
         if (__nested instanceof Map<?,?> __m && __m.containsKey("landkode")) {
             sets.put(t.LANDKODE, DSL.val(__m.get("landkode"), t.LANDKODE.getDataType()));
         }
     }
     ```
     The presence semantics across nesting layers need to be picked deliberately: a missing outer key, a null outer value, a present-outer-but-missing-leaf, and a present-outer-but-null-leaf are all distinct on the wire. Default contract: *each layer's presence is independent* — missing or null at any layer skips the leaf write. This is a *deliberate fork*, not an inevitable one. The alternative — "outer presence implies leaf-write-attempted" (PATCH-semantic: sending `lokalisering: { landkode: null }` writes `LANDKODE = NULL`) — is what consumers reaching for nested groupings typically model. We pick emitter-simple over consumer-intuitive here because the simpler contract composes uniformly across all four DML verbs and because the PATCH-semantic alternative is observably weaker on the wire only when the consumer writes a present-outer-but-null-leaf — a narrow case. Revisit if a forcing-function schema appears. Document the chosen contract in the user docs verbatim so the trade-off is visible to consumers.
   * Apply the same refactor to the bulk-INSERT row walkers (`in.get(rowIdx)` becomes the row-local map; from there the same access-path walk produces the cell read) and to the SET-via-EXCLUDED upsert arm.

5. **`InputColumnBindingGroup` shape**:
   * `MapGroup` today carries an SDL field name as the wire-format key. Either extend the record to carry an access path (default singleton) or replace the name with a path field. The MutationInputResolver pre-step (R94's input-record validator surface) already recurses into nested input components via `fromMap`, so the validator side composes without change once `InputColumnBindingGroup` knows the access path.

6. **User documentation** (first-client check):
   * Section in `docs/manual/tutorial/05-mutations.adoc`: "Grouping fields with nested input types". Show the `EndreOrganisasjonInput` / `LokaliseringInput` forcing-function schema verbatim, walk the resulting mutation call (`mutation { endreOrganisasjon(input: [{ id, originalnavn, lokalisering: { landkode, bynavn } }]) }`), and call out three things: (a) the nested grouping has no DML semantics — it's purely a wire-format ergonomics shape; (b) `@field(name:)` on a nested leaf targets the outer table; (c) each nesting layer's presence is independent of its parent's. The chapter has no current section on nested input shapes; insertion point is right after the multi-column `@mutation(typeName: UPDATE)` example.
   * Cross-reference from `docs/manual/reference/directives/mutation.adoc`: one paragraph in the "Input shape" subsection noting that nested non-`@table` inputs are admitted as a grouping shape, with a pointer to the tutorial chapter for the worked example.
   * No changes needed to `directives/table.adoc` (the nested grouping is *not* `@table`-backed) or to the diagnostics glossary unless step 1's `@value`-on-NestingField rejection ships before R188 retires `@value` — in which case the glossary gains one entry.

7. **Existing audit key update** (`MutationInputResolver.java` `@LoadBearing` annotation, retained during the R237 retirement gap window):
   * The Invariant #4 audit key `mutation-input.update-set-fields-equal-value-marked` (MutationInputResolver.java:319-323) generalises: `setFields()` on UPDATE is exactly the `@value`-marked admissible *leaves* (flat-projected), in SDL declaration order with `NestingField` children expanded in place. Update the `description` text accordingly; the assertion code holds unchanged because it reads the flat leaf list.
   * The access-path invariant for nested-leaf extractions (every leaf flattened out of a `NestingField` carries a `CallSiteExtraction.NestedInputField` whose access path's first segment is the immediately-enclosing `NestingField`'s SDL name) is mechanically pinned by the sealed-variant carrier on the leaf's `extraction` slot and by the pipeline-tier coverage step 4 above; no new producer key is filed.

### Interaction with neighbouring items

* **R188 (replace `@value` with PK-default partition)**: orthogonal on the partition axis but interacts on the `@condition` axis. The factory flattening runs before partition, so post-R188 the partition becomes "is leaf's resolved column in the PK" instead of "is leaf's access path in `valueMarkedNames`" — that part is partition-agnostic. The `@condition` interaction is sharper: R188 introduces a layered `WhereFilters` sub-taxonomy (`InputTypeLayer` / `InputFieldLayer` / etc.) on `TableInputArg`, and `InputFieldLayer` is keyed by SDL field name with no slot for an access path. A `@condition` on a `NestingField` leaf has nowhere to attach in that layered shape. Two viable resolutions: (a) widen `InputFieldLayer` to carry an access path (singleton for non-nested leaves, multi-segment for nested), or (b) defer nested-leaf `@condition` to a follow-up item and reject it during admission. *Land R188 first* if practical so R186's path-rewrap step doesn't first introduce a typed shape (`Set<List<String>>` of `valueMarkedNames`) that R188 immediately discards; the carrier-of-path claim (`CallSiteExtraction.NestedInputField` rewrap) survives either ordering, so R186-first is technically possible but adds churn.
* **R122 (compound mutations)**: strictly disjoint. R122 admits nested *`@table`-backed* inputs that introduce additional DML targets; R186 admits nested *non-`@table`* inputs that flatten onto the outer DML target. The two arms in the BuildContext nesting branch (table-bearing vs. non-table-bearing nested input objects) stay clearly separated.
* **R189 (FK-target `@nodeId` carriers on `@mutation` inputs)**: composes. A `NestingField` leaf can carry `@nodeId(typeName: T)` and produce a `ColumnReferenceField` / `CompositeColumnReferenceField` exactly as it would at the input root. The R189 admission predicate is leaf-local; R186's flattening preserves the carrier shape, so the predicate matches transparently.
* **R171 (sealed `InputLikeType` parent)**: independent. No collision; both can land in either order.

## Tests

Tier choices reflect the project's test-pyramid guidance.

* **Pipeline tier (`GraphitronSchemaBuilderTest`)** — primary classification coverage:
  * `DML_NESTING_FIELD_ADMITTED`: replace today's `DML_NESTING_FIELD_DEFERRED` case (asserts the rejection) with one that asserts admission. Forcing-function schema is the R186 body's `EndreOrganisasjonInput` shape against a `@table(name: "organisasjon")` plus `LokaliseringInput { landkode, bynavn, regionnavn }` mapping to columns on the outer table. Assert the resulting `MutationField.MutationUpdateTableField`'s `setFields()` contains the flattened leaves in SDL declaration order with their access paths.
  * `DML_NESTING_LIST_REJECTED`: list-typed nested input (`lokalisering: [LokaliseringInput!]`) trips `Rejection.structural` naming R186.
  * `DML_NESTING_VALUE_ON_NESTING_REJECTED`: `@value` on a `NestingField` carrier trips the per-field rejection (drop on R188 landing).
  * `DML_NESTING_UNRESOLVABLE_LEAF`: nested input with a leaf that doesn't resolve to an outer-table column produces `UnclassifiedType` with the existing candidate-hint message (regression assertion: the nesting branch's error path is unchanged).
  * `DML_NESTING_DEEP`: two layers deep (`a: { b: { c: String } }`) classifies the same way the one-layer case does; assert the access path is `["a", "b", "c"]`.
  * `DML_INSERT_NESTING_OK`, `DML_DELETE_NESTING_OK`, `DML_UPSERT_NESTING_OK`: one case per remaining verb, smaller scope (one leaf each) to confirm DML-verb-coverage admission.
  * `DML_NESTING_WITH_NODEID_FK_TARGET`: a `NestingField` leaf carrying `@nodeId(typeName: T)` against an FK-target NodeType produces `ColumnReferenceField` under the nesting, with `liftedSourceColumns` on the outer table.

* **Compilation tier (`SakilaCompilationTest` or equivalent)**: one fixture schema with the forcing-function `EndreOrganisasjonInput` shape against a Sakila table (or fixtures-codegen schema if the URegOrganisasjon shape is too narrow). Verify generated Java compiles under Java 17. Catches emit-site refactor bugs in step 4 that the classification tests can't see.

* **Execution tier (Sakila DB via `SakilaServiceTest`)**: one end-to-end execution test per DML verb (INSERT, UPDATE, UPSERT, DELETE) using a small nested-input shape against an actual Sakila table. Confirms the access-path walk in the emitted Java produces the right SQL against a real PG, that each layer's presence semantics work as documented (missing outer key, null outer value, missing leaf, null leaf are observable on the wire and the generated code handles each), and that PK-coverage still trips when expected.

* **LSP tier (`LspNodeTypeHover` or equivalent)**: confirm hover-on-leaf inside a `NestingField` reports the outer table's column (the lookup chain that already works for top-level leaves should compose transparently — but assert it to lock in the behaviour).

## Roadmap entries

When this item completes:

* Remove `DML_NESTING_FIELD_DEFERRED` from the pipeline-test enum; the deferred case becomes the admitted case.
* Add `changelog.md` entry capturing the landing SHA and the new admitted shape (this is the kind of milestone worth keeping in the changelog).
* Update `MutationInputResolver`'s class-level `@LoadBearing` description so the "Invariant #7 (nested input)" wording reflects admission rather than rejection.
* The "R128 / R122 territory" comment at `MutationInputResolver.java:477-478` deletes with the rejection arm.

## Out of scope

- Nested `@table` inputs that introduce a second DML target — that's R122's territory.
- Nested inputs whose leaves are themselves `@table`-backed shapes (R23's multi-parent territory on the output side has no symmetric input meaning yet).
- List-typed nested inputs (`lokalisering: [LokaliseringInput!]`) — rejected in step 1 of Implementation; revisit when a forcing-function schema appears.
- Validation-side composition of nested input shapes with `@validator` / `@constraint`: R94's input-record shape already recurses through nested components via `fromMap`, so the validation surface composes; no new validator work needed here.
