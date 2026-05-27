---
id: R188
title: "Replace @value with PK-default partition on UPDATE/DELETE"
status: Spec
bucket: cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-05-27
---

# Replace @value with PK-default partition on UPDATE/DELETE

UPDATE mutations today partition `@table` input fields by an explicit `@value` directive: marked fields become SET, unmarked fields become WHERE. The partition is mechanical and the schema already implies it via PK metadata; PK columns identify the row (WHERE), non-PK columns carry the new values (SET). The `@value` directive and its paired structural diagnostics ("no @value fields", "every field is @value-marked") exist to police a partition the catalog already knows.

R188 drops `@value`; UPDATE's SET/WHERE partition becomes PK-vs-non-PK from the jOOQ catalog. `@condition` on mutations is out of scope; see R245 for the emit-side wiring (the R215 admit-but-no-emit closeout). R188 leaves `@condition` in its current half-functional state and removes only the `@value`-related interactions.

## Design

### Partition (`@table` input → SET / WHERE)

The SET/WHERE partition becomes purely PK-driven, read from the jOOQ catalog. **One rule across all four admissible carriers**: for each input field, look at its target columns on the input's own table:

* `ColumnField.column()` (single direct)
* `CompositeColumnField.columns()` (composite direct, R130 NodeId → composite PK)
* `ColumnReferenceField.liftedSourceColumns()` (single FK reference, R189)
* `CompositeColumnReferenceField.liftedSourceColumns()` (composite FK reference)

Then:

* If **every** target column is in the table's PK → `lookupKeyFields` (WHERE).
* If **no** target column is in the table's PK → `setFields` (SET).
* **Mixed** (some PK, some non-PK) → reject at classify time with a structural diagnostic naming the conflicting columns. The partition is per-field; splitting one carrier across two partitions would require a model change inconsistent with this spec's stance.

By construction, the mixed case only arises on `CompositeColumnReferenceField` (composite FK that's a diagonal slice of the input table's PK). `CompositeColumnField` only originates from R130's NodeId → composite-PK decode, so its column set is always the full PK; the all-PK arm always fires. Single carriers cannot be mixed.

This unification turns "update an FK column" into a natural shape: an FK reference carrier whose lifted source column is non-PK lands in SET (e.g. `updateFilm(in: { filmId, languageId })` where `languageId` is a non-PK FK; the carrier writes the new language). A reference whose lifted source column is part of the PK lands in WHERE (e.g. a join-table UPDATE where one PK column is itself an FK).

INSERT and DELETE arms are untouched by the partition change. INSERT has no partition (every input field is a VALUES cell); DELETE has no SET clause. Removing `@value` deletes the `@value`-rejection clauses on these arms.

**DELETE input fields are PK-only by the same four-carrier rule.** Today's DELETE inputs are PK-only by directive position (a non-PK column on a DELETE input could only be `@value`-marked, which DELETE rejects, or unmarked, which would put a non-PK column into the implicit WHERE, silently broad). Under R188, the rule becomes explicit: a `ColumnField` / `CompositeColumnField` / `ColumnReferenceField` / `CompositeColumnReferenceField` whose target columns on the input's own table contain any non-PK column is rejected at classify time on DELETE with diagnostic key `mutation-input.delete-input-field-non-pk` (message: "input field 'X' targets non-PK columns on DELETE; DELETE has no SET clause to receive the value. Move the column to a query argument."). Mirrors UPDATE's "no non-PK columns to set" diagnostic shape and removes the only path by which a DELETE could pick up a non-PK column predicate implicitly.

### Tables without a PK

`JooqCatalog.findPkColumns(table)` returns empty when the input's `@table` has no primary key declared in the catalog. Under the four-carrier rule above, every input field then falls into the "no target column in PK" arm and lands in `setFields`; `lookupKeyFields` is empty. The R144 disjunction then rejects the field unless `multiRow: true` is set, but the diagnostic the schema author sees is R144's generic "where-identifies-row", pointing at row identity rather than at the missing catalog PK.

R188 adds a dedicated classify-time rejection at the moment the PK lookup returns empty on a verb that requires PK coverage (UPDATE / DELETE without `multiRow:`). Diagnostic key `mutation-input.table-has-no-pk`, message naming the table and pointing at the two escape hatches (declare a PK on the table, or set `multiRow: true`). The validator mirrors the same check at SDL-walk time so the catalog-migration case ("PK dropped from `film`") fails loudly at the post-migration build rather than silently re-classifying every UPDATE/DELETE on the table as broadcast.

### Schema-legibility trade

Under `@value`, the SET/WHERE partition was visible in the SDL. Under R188, a reader looking at `FilmUpdateInput.title` must know `filmId` is the PK in `film` to predict that `title` lands in SET. This is a real legibility reduction, accepted because the information lives in the catalog and the two structural diagnostics it gated existed to police a partition the catalog already knew. The user-doc rewrite names the inference rule once.

### PK-change consequence

A catalog migration that moves a column in or out of the PK re-partitions every UPDATE input that includes the affected column at next build. The new behaviour is correct (the new PK is the new row identity) and surfaces at build time: any UPDATE that loses its row-identity proof fails classification. The change is silent in the SDL but loud in the build; no additional guard is added.

UPSERT is deferred to R145; R145's body is updated so its conflict-target / SET partition uses the PK-default rule rather than re-introducing `@value`.

## Implementation

* `directives.graphqls:220-231`: remove `@value` directive declaration and doc block.
* `BuildContext.java:97`: remove `DIR_VALUE`; audit imports.
* `GraphitronSchemaBuilder.java:495`: remove `assertDirective(ctx, DIR_VALUE)`.
* `DmlKind.java`: delete `acceptsValueMarker()` and callers.
* `ArgumentRef.java`: rework `TableInputArg.of(...)` to drop the `valueMarkedNames` parameter; derive the partition from PK metadata read off `inputTable.tableName()` via `JooqCatalog`. The factory grows a `JooqCatalog` (or precomputed PK column set) argument; threading site is `MutationInputResolver.resolveInput`. The factory applies the four-carrier unified rule from "Partition" above: per field, collect target columns on the input's own table (`.column()` / `.columns()` / `.liftedSourceColumns()` per carrier type), classify all-PK / no-PK / mixed, and route to `lookupKeyFields`, `setFields`, or rejection respectively. Update the `set-equals-value-marked` audit-key javadoc at line ~262 to describe the new PK-driven rule and rename the audit key to `mutation-input.partition-equals-pk-membership`.
* `EnumMappingResolver.java`: refactor `buildLookupBindings` to read the PK set from the catalog rather than from a marked-name set.
* `MutationInputResolver.java`:
  * Drop the `valueMarkedNames` accumulation loop (current ~356-401) and the rejection sites it gates: `@value` on DELETE/INSERT, `@value` + `@condition` co-occurrence.
  * Drop the UPDATE-specific structural diagnostics (current ~509-520). The new failure mode (PK in input, no non-PK columns) becomes a single check: "no non-PK columns to set; UPDATE has nothing to write."
  * Add a `mutation-input.table-has-no-pk` rejection on UPDATE / DELETE when `JooqCatalog.findPkColumns(inputTable.tableName())` returns empty and `multiRow` is false. The check runs before the per-field admission loop so the diagnostic surfaces at the table level, not as a cascade of per-field "no target column" failures.
  * Add a `mutation-input.delete-input-field-non-pk` rejection on DELETE for any column-bound carrier whose target columns on the input's own table include a non-PK column. The check piggy-backs on the four-carrier classifier walk Strand A adds (run the PK-membership categorisation; on DELETE, the no-PK and mixed arms both reject; the all-PK arm contributes to the implicit WHERE).
  * Add a `mutation-input.composite-reference-mixes-pk-and-non-pk` rejection for `CompositeColumnReferenceField` whose lifted source columns mix PK and non-PK on the input's own table. Diagnostic names the field and enumerates which columns are PK / non-PK.
* `TypeFetcherGenerator.java`: re-word `@value`-referencing javadoc to "non-PK admissible carriers." No behavioural change to the SET/WHERE walk.

## Schema migration

* `directives.graphqls:200-201`: rewrite the `@value`-mentioning sentence in `@lookupKey`'s retired-on-`INPUT_FIELD_DEFINITION` docstring ("on `@mutation(typeName: UPDATE)` inputs, mark assignment columns with `@value` (which `@lookupKey` previously did by negation)") to describe the new PK-driven inference rule instead.
* `schema.graphqls:1232-1245`: strip `@value` from `FilmUpdateInput.title` and `FilmUpdateInput.description`; rewrite the comment block to describe PK-driven inference. No structural change to the input.
* `GraphitronSchemaBuilderTest.java`: strip `@value` from every embedded SDL snippet (locate via `grep -n '@value'`; ~30 sites). Cases that exercised deleted diagnostics convert:
  * "UPDATE without any `@value` fields" (current ~6841-6852) becomes "UPDATE with only PK columns; no non-PK to set."
  * "UPDATE with every input `@value`-marked" (current ~6854) becomes "UPDATE with no PK columns, no `multiRow:`; row-identity disjunction fails." With `multiRow: true` the same shape admits.
  * "`@value` on DELETE" and "`@value` + `@condition` mutually exclusive" (current ~7370-7398) delete outright.
  * The TableInputArg projection test (current ~6945) keeps its shape; partition source is PK-vs-non-PK instead of `@value`.
* `SingleRecordPayloadPipelineTest.java:377-382, 610-612`: strip `@value`. UPSERT cases continue to use the UPSERT-rejection diagnostic until R145.
* `FetcherPipelineTest.java:446, 447, 493, 494, 565, 566`: strip `@value`.
* `MutationDmlNodeIdClassificationTest.java:100-103, 148, 176, 459, 473`: strip `@value`; adjust the diagnostic-text assertion at 103 to "no non-PK columns to set."

## Tests

Tier discipline per `rewrite-design-principles.adoc:126-140`.

### Pipeline (primary)

* `GraphitronSchemaBuilderTest` cases above migrate to the new diagnostics and PK-default partition.
* Broadcast admit: UPDATE with no PK columns, no `@condition`, `multiRow: true` → admit. Pins R144 semantics under R188.
* Empty-SET reject: UPDATE with only PK columns → `UnclassifiedField` with "no non-PK columns to set."
* FK reference on a non-PK column lands in SET: `updateFilm(in: { filmId, languageRef })` where `languageRef` is a `ColumnReferenceField` whose lifted source is `language_id` (non-PK) → `setFields` contains the reference carrier, `lookupKeyFields` contains `filmId`. Pins the four-carrier unified rule against the "references always WHERE" reading.
* FK reference on a PK column lands in WHERE: a join-table UPDATE input where one PK column is itself an FK → reference carrier lands in `lookupKeyFields`.
* `CompositeColumnReferenceField` with diagonal PK overlap: classify-time reject with `mutation-input.composite-reference-mixes-pk-and-non-pk` naming the field and per-column PK membership.
* Table without a PK in the catalog: classify-time reject UPDATE and DELETE (no `multiRow:`) with `mutation-input.table-has-no-pk` naming the table. The same shape under `multiRow: true` admits. Pins the dedicated diagnostic against the R144 generic message.
* DELETE input field whose target column is non-PK (single-column `ColumnField`): classify-time reject with `mutation-input.delete-input-field-non-pk`. The same field on UPDATE admits and lands in `setFields` (the "FK on non-PK column" case is the UPDATE-side mirror); pins that DELETE is the strict side.

### Compilation

`mvn compile -pl :graphitron-sakila-example -Plocal-db` passes after `@value` removal.

### Execution

`DmlMutationsExecutionTest` / `DmlBulkMutationsExecutionTest` cover round trips. Default-path tests do not change (PK partition is structurally identical).

### Unit

None.

## User documentation (first-client check)

* **Delete** `docs/manual/reference/directives/value.adoc`. Not cross-referenced from `directives/index.adoc` (the alphabetical and category lists at `docs/manual/reference/directives/index.adoc:12-38` do not mention it); no inbound xrefs.
* **Revise** `docs/manual/reference/directives/mutation.adoc`. The current prose at lines 65 and 120-123 describes the UPDATE partition in terms of `@lookupKey` (the user docs are already out of step with code under the `@value` regime; R188 brings the docs into alignment with the post-R188 PK-driven rule rather than the never-shipped `@value` doc shape). Replace those sections with:

[quote]
____
`UPDATE` partitions the `@table` input fields by primary-key membership: PK columns drive the WHERE clause (the row to update), non-PK columns drive the SET clause (the new values). The partition is read from the table catalog; no per-field directive is required.

[source,graphql]
----
input FilmUpdateInput @table(name: "film") {
    filmId: ID!    @field(name: "film_id")
    rating: String @field(name: "rating")
    title: String  @field(name: "title")
}
----

When the WHERE side does not reduce to at most one row by construction (no PK in input), set `multiRow: true` on the `@mutation` directive to acknowledge the broadcast intentionally.
____

If the result does not read simply, the design is wrong; the draft above is short and self-contained.

## Roadmap entries

* **R145 (UPSERT, `mutation-cardinality-safety-upsert.md`):** R145's conflict-key axis is unchanged. R188's contribution: UPSERT's SET clause becomes "input columns not in the conflict-target column set" (PK-default rule generalised to conflict-key-default). The R145 body edit lands in the same commit that drops `@value`. No status change.
* **R146 (`mutation-cardinality-safety-unique-index.md`):** Stays Backlog. Under R188, "uniquely identified by an alternate unique key from input columns" without writing an override predicate no longer arises as a distinct shape. R146 reopens if a future schema needs that shape.
* **R245 (`wire-condition-emit-on-mutations.md`):** Picks up the `@condition`-on-mutations wiring (input field with/without override, non-`@table` argument) that closes R215's emit half. R245 depends on R188's PK-default partition as its baseline; the spec there layers `@condition` predicates on top of it.
* **R130 / R144:** both shipped; R188 is the next iteration of the same partition story. Add a `changelog.md` entry on R188's Done: `@value` directive removal and the new PK-default partition rule.

## Out of scope

* `@condition` on mutations (all placements). Owned by R245.
* Validating the `@condition` method's body. Owned by R245 / author responsibility.
* INSERT is not restructured. INSERT has no WHERE clause; INSERT-side `@condition` rejection is owned by R245.
* UPSERT. Owned by R145.
