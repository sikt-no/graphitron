---
id: R188
title: "Replace @value with PK-default partition + @condition on mutations"
status: Spec
bucket: cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-05-26
---

# Replace @value with PK-default partition + @condition on mutations

UPDATE mutations today partition `@table` input fields by an explicit `@value` directive: marked fields become SET, unmarked fields become WHERE. The partition is mechanical and the schema already implies it via PK metadata; PK columns identify the row (WHERE), non-PK columns carry the new values (SET). The `@value` directive and its paired structural diagnostics ("no @value fields", "every field is @value-marked") exist to police a partition the catalog already knows.

`@condition` on the mutation surface is half-built today: argument-level `@condition` on a `@mutation` field is rejected outright (`MutationInputResolver.java:446`), and input-field-level `@condition` admits only with `override: true` and is silently dropped at emit (R215, `MutationInputResolver.java:482-498`). R188 closes both.

R188 has two strands:

* **Strand A:** drop `@value`; UPDATE's SET/WHERE partition becomes PK-vs-non-PK from the jOOQ catalog.
* **Strand B:** wire `@condition` through to mutation WHERE at two placements (input field, non-`@table` mutation argument). Closes R215's outstanding gap.

The strands are independent; either can land first.

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
* **Mixed** (some PK, some non-PK) → reject at classify time with a structural diagnostic naming the conflicting columns. The partition is per-field; splitting one carrier across two partitions would require a model change inconsistent with R188's "no sealed RowIdentity" stance.

`@condition` does not move fields between partitions. It contributes predicates to WHERE; it does not remove columns from SET.

By construction, the mixed case only arises on `CompositeColumnReferenceField` (composite FK that's a diagonal slice of the input table's PK). `CompositeColumnField` only originates from R130's NodeId → composite-PK decode, so its column set is always the full PK; the all-PK arm always fires. Single carriers cannot be mixed.

This unification turns "update an FK column" into a natural shape: an FK reference carrier whose lifted source column is non-PK lands in SET (e.g. `updateFilm(in: { filmId, languageId })` where `languageId` is a non-PK FK; the carrier writes the new language). A reference whose lifted source column is part of the PK lands in WHERE (e.g. a join-table UPDATE where one PK column is itself an FK).

INSERT and DELETE arms are untouched by the partition change. INSERT has no partition (every input field is a VALUES cell); DELETE has no SET clause. Removing `@value` deletes the `@value`-rejection clauses on these arms.

### `@condition` placements on mutations

Three placements are admitted:

| Placement | Effect | Use when |
|---|---|---|
| Input field, no `override:` | Predicate AND-s into WHERE alongside the implicit PK predicates. | Per-row gate: "update by ID, but only if status matches." |
| Input field, `override: true` | Predicate replaces the implicit PK WHERE. | Row identified by something other than PK. R215 wiring. |
| Non-`@table` mutation argument | Predicate AND-s into WHERE, shared across all rows in a bulk call. | Per-call filter token: tenant ID, search term, soft-delete flag. |

Explicitly not admitted in this spec:

* `@condition` on the `@mutation` field itself. No forcing function; reachable from the placements above.
* `@condition` on the input type (`INPUT_OBJECT` SDL location is not added to the directive). No forcing function.
* `@condition` on the `@table` arg of a `@mutation` field. Same rejection as today; diagnostic recommends an input field or non-`@table` argument instead.

If either omitted placement turns out to be painful in practice, it gets filed as a follow-up roadmap item with a concrete forcing case attached.

### Composition

All non-override `@condition` predicates AND together. At most one input-field-level `@condition(override: true)` is permitted per input (two overrides on different fields each claiming the row-spec is ambiguous; reject).

When an override is present, it replaces the implicit PK WHERE; non-override predicates still AND in. When no override is present, the implicit PK predicates drive WHERE and the non-override predicates AND alongside. Inner explicit `@condition`s are always preserved regardless of override (the `filmsOuterOverrideTableInput` regression-fence applies on the mutation side).

### Partition vs predicate, made explicit

The SET/WHERE partition is a classifier output read from PK metadata. `@condition` contributes predicates AND-ed into WHERE. The two never share a column: a column is either in SET (non-PK input field) or in the implicit PK WHERE (PK input field) or as a parameter to a `@condition` method (any scalar reused as predicate input).

### Row-identity disjunction

The R144 check stays as-is: the field has a row-identity proof iff at least one of

* PK columns are covered by input fields, OR
* an input-field `@condition(override: true)` is present, OR
* `multiRow: true` is set on the `@mutation` directive

holds; else the field is `UnclassifiedField` via `mutation-input.where-identifies-row`. No sealed `RowIdentity` taxonomy; the disjunction is checked directly in `MutationInputResolver`, and the emitter forks once on the three cases. The validator's invariant is the same disjunction R144 already pins. The boolean shape is intentionally retained: the three cases share the same `.where(...)` emit shape (just different ingredients), so a sealed lift would re-introduce a fork on payload presence inside the variant rather than eliminating one.

### Schema-legibility trade

Under `@value`, the SET/WHERE partition was visible in the SDL. Under R188, a reader looking at `FilmUpdateInput.title` must know `filmId` is the PK in `film` to predict that `title` lands in SET. This is a real legibility reduction, accepted because the information lives in the catalog and the two structural diagnostics it gated existed to police a partition the catalog already knew. The user-doc rewrite names the inference rule once.

### PK-change consequence

A catalog migration that moves a column in or out of the PK re-partitions every UPDATE input that includes the affected column at next build. The new behaviour is correct (the new PK is the new row identity) and surfaces at build time: any UPDATE that loses its row-identity proof fails classification. The change is silent in the SDL but loud in the build; no additional guard is added.

UPSERT is deferred to R145; R145's body is updated so its conflict-target / SET partition uses the PK-default rule rather than re-introducing `@value`. See "Roadmap entries" below.

## Implementation

### Strand A: drop `@value`, PK-default partition

* `directives.graphqls:220-231`: remove `@value` directive declaration and doc block.
* `BuildContext.java:97`: remove `DIR_VALUE`; audit imports.
* `GraphitronSchemaBuilder.java:495`: remove `assertDirective(ctx, DIR_VALUE)`.
* `DmlKind.java`: delete `acceptsValueMarker()` and callers.
* `ArgumentRef.java`: rework `TableInputArg.of(...)` to drop the `valueMarkedNames` parameter; derive the partition from PK metadata read off `inputTable.tableName()` via `JooqCatalog`. The factory grows a `JooqCatalog` (or precomputed PK column set) argument; threading site is `MutationInputResolver.resolveInput`. The factory applies the four-carrier unified rule from "Partition" above: per field, collect target columns on the input's own table (`.column()` / `.columns()` / `.liftedSourceColumns()` per carrier type), classify all-PK / no-PK / mixed, and route to `lookupKeyFields`, `setFields`, or rejection respectively. Update the `set-equals-value-marked` audit-key javadoc at line ~262 to describe the new PK-driven rule and rename the audit key to `mutation-input.partition-equals-pk-membership`.
* `EnumMappingResolver.java`: refactor `buildLookupBindings` to read the PK set from the catalog rather than from a marked-name set.
* `MutationInputResolver.java`:
  * Drop the `valueMarkedNames` accumulation loop (current ~356-401) and the rejection sites it gates: `@value` on DELETE/INSERT, `@value` + `@condition` co-occurrence.
  * Drop the UPDATE-specific structural diagnostics (current ~509-520). The new failure mode (PK in input, no non-PK columns, no override) becomes a single check: "no non-PK columns to set; UPDATE has nothing to write."
* `TypeFetcherGenerator.java`: re-word `@value`-referencing javadoc to "non-PK admissible carriers." No behavioural change to the SET/WHERE walk.

### Strand B: `@condition` at two mutation placements

**Admission and resolution**

* `MutationInputResolver.java`:
  * Lift the rejection at lines 438-440 (`if (foundTia.argCondition().isPresent()) { return ...rejection... }`): argument-level `@condition` on a non-`@table` argument of a `@mutation` field is admitted. `@condition` on the `@table` arg stays rejected with a diagnostic recommending an input field or non-`@table` argument.
  * Admit input-field-level `@condition` without `override:` (today only `override: true` admits via R215, per `MutationInputResolver.java:482-498`). Both override and non-override forms now compose into the field record.
* `ConditionResolver.resolveArg(...)` is already in place; no new resolver surface required.

**Model**

`MutationField.java`: `MutationUpdateTableField`, `MutationDeleteTableField`, and `MutationDmlRecordField`'s DELETE-equivalent path gain two slots:

* `Optional<ConditionFilter> overrideCondition`: the composed override predicate (at most one input-field-level `@condition(override: true)`, plus any inner non-override `@condition`s AND-ed for the regression-fence). Absent when no input field carries `override: true`.
* `List<ConditionFilter> additionalConditions`: non-override `@condition`s from input fields and non-`@table` arguments, in source order.

`multiRow:` stays on `@mutation` as today. `MutationUpsertTableField` gets the same two slots prospectively for R145's tenant-scoping use; UPSERT's conflict target is a separate axis.

The composition step in `MutationInputResolver.resolveInput` reads input-field-level `@condition` annotations off `InputField.condition()`, reads each non-`@table` argument's directive via `ConditionResolver.resolveArg`, applies the at-most-one-override rule, and writes the two slots.

**Emitter cascade**

`TypeFetcherGenerator.java` UPDATE and DELETE arms (single-row, bulk, and payload-returning variants) fork once on slot state:

```
if (overrideCondition.isPresent())  → .where(override AND additionalConditions...)
else if (multiRow)                  → no WHERE; additionalConditions, if any, AND-ed in
else                                → .where(implicitPk AND additionalConditions...)
```

Threading: each `ConditionFilter`'s reflected `MethodRef.Param` extractions resolve against the call-site `Map` exactly as today's argument-level `@condition` does. For the bulk arm, the filter operates over the v-table reference, matching query-side bulk-condition emission.

The emitter never re-derives the disjunction; the slots are pre-resolved at classify time.

**Reflection-shape invariant**

Each mutation-side `@condition` method takes `(Table t, <scope-appropriate scalars>)`: a subset of the input's field set, or the surrounding argument's value. Never a per-row jOOQ record; the bulk-emit path has no v-table analogue. `argMapping:` is supported (rebind GraphQL field names to differently-named Java parameters), mirroring query-side leaf `@condition`. Enforced at reflection time in `ConditionResolver`, named `mutation-condition.method-shape-table-plus-scope-scalars`.

This narrows the mutation-side reflection contract against the query-side one (which permits row-record parameters via different paths). The uniform narrowing across single-row and bulk mutations is a deliberate choice; mixing per-row-record and v-table shapes inside one directive would require classify-time bulk awareness the resolver doesn't have today.

**Structural rules**

Applied at classify time in `MutationInputResolver`:

* **At most one input-field-level `override: true` per input.** Two overrides on different fields each claim row-identity responsibility; reject with a diagnostic naming the conflicting fields.
* **`@condition` on the `@table` input arg stays rejected.** Diagnostic: "argument-level `@condition` on the `@table` input arg is rejected; put it on a non-`@table` argument or on an input field of the `@table` input."
* **`@condition(override: true)` on a column-bound non-PK input field is rejected as no-op-override.** A non-PK field has no implicit predicate to suppress (the partition is pure; non-PK columns contribute to SET). Diagnostic names the field and suggests dropping the `override:` flag.
* **`CompositeColumnReferenceField` whose lifted source columns mix PK and non-PK is rejected as ambiguous.** Diagnostic names the field and enumerates which columns are PK / non-PK. A schema with this shape (a composite FK that's a diagonal slice of the input table's PK) is architecturally rare; if a forcing case surfaces, file a follow-up roadmap item to lift the partition to per-column. By construction the case cannot arise on the other three carriers (`ColumnField` / `ColumnReferenceField` are single-column; `CompositeColumnField` from R130 is always the full PK).
* **Inner explicit `@condition`s are always preserved.** The `filmsOuterOverrideTableInput` regression-fence applies on the mutation side; the composition step AND-s inner non-override filters into the override slot when override is present.

### Strand coupling

The strands are independent: Strand A's empty-SET diagnostic ("no non-PK columns to set") fires when no override is present, so Strand B's override-flag readout does not gate Strand A's structural checks. Either strand may land first.

## Schema migration

* `directives.graphqls:200-201`: rewrite the `@value`-mentioning sentence in `@lookupKey`'s retired-on-`INPUT_FIELD_DEFINITION` docstring ("on `@mutation(typeName: UPDATE)` inputs, mark assignment columns with `@value` (which `@lookupKey` previously did by negation)") to describe the new PK-driven inference rule instead.
* `schema.graphqls:1232-1245`: strip `@value` from `FilmUpdateInput.title` and `FilmUpdateInput.description`; rewrite the comment block to describe PK-driven inference. No structural change to the input.
* `GraphitronSchemaBuilderTest.java`: strip `@value` from every embedded SDL snippet (locate via `grep -n '@value'`; ~30 sites). Cases that exercised deleted diagnostics convert:
  * "UPDATE without any `@value` fields" (current ~6841-6852) becomes "UPDATE with only PK columns; no non-PK to set."
  * "UPDATE with every input `@value`-marked" (current ~6854) becomes "UPDATE with no PK columns, no override, no `multiRow:`; row-identity disjunction fails." With `multiRow: true` the same shape admits.
  * "`@value` on DELETE" and "`@value` + `@condition` mutually exclusive" (current ~7370-7398) delete outright.
  * The TableInputArg projection test (current ~6945) keeps its shape; partition source is PK-vs-non-PK instead of `@value`.
* `SingleRecordPayloadPipelineTest.java:377-382, 610-612`: strip `@value`. UPSERT cases continue to use the UPSERT-rejection diagnostic until R145.
* `FetcherPipelineTest.java:446, 447, 493, 494, 565, 566`: strip `@value`.
* `MutationDmlNodeIdClassificationTest.java:100-103, 148, 176, 459, 473`: strip `@value`; adjust the diagnostic-text assertion at 103 to "no non-PK columns to set."

## Tests

Tier discipline per `rewrite-design-principles.adoc:126-140`.

### Pipeline (primary)

Strand A:

* `GraphitronSchemaBuilderTest` cases above migrate to the new diagnostics and PK-default partition.
* Broadcast admit: UPDATE with no PK columns, no `@condition`, `multiRow: true` → admit. Pins R144 semantics under R188.
* Empty-SET reject: UPDATE with only PK columns → `UnclassifiedField` with "no non-PK columns to set."
* FK reference on a non-PK column lands in SET: `updateFilm(in: { filmId, languageRef })` where `languageRef` is a `ColumnReferenceField` whose lifted source is `language_id` (non-PK) → `setFields` contains the reference carrier, `lookupKeyFields` contains `filmId`. Pins the four-carrier unified rule against the "references always WHERE" reading.
* FK reference on a PK column lands in WHERE: a join-table UPDATE input where one PK column is itself an FK → reference carrier lands in `lookupKeyFields`.
* `CompositeColumnReferenceField` with diagonal PK overlap: classify-time reject with the new `mutation-input.composite-reference-mixes-pk-and-non-pk` diagnostic naming the field and per-column PK membership.

Strand B (each admit case asserts the slot values on the field record, not just admit/reject):

* Input-field `@condition`, no `override:`, PK in input: admit; WHERE = `pk = ? AND condition(?)`. Single-row and bulk variants.
* Input-field `@condition(override: true)`, PK NOT in input: admit; WHERE = `condition(?)` only. Override slot populated; `additionalConditions` empty.
* Input-field `@condition(override: true)`, PK in input: admit; WHERE = `condition(?)`. Override suppresses implicit PK. SET still contains every non-PK column.
* Non-`@table` argument `@condition`: admit; WHERE = `pk = ? AND argCondition(?)`. Lifts the line-446 rejection.
* `@condition` on the `@table` arg: reject with the migration-pointing diagnostic.
* `@condition(override: true)` on a column-bound non-PK input field: reject as no-op.
* Two input fields with `@condition(override: true)`: reject (at-most-one-override).
* Mixed layers: one input field with `override: true` plus one input field with non-override `@condition`: admit; override populates the override slot, non-override populates `additionalConditions`, both compose into WHERE.
* `multiRow: true` plus non-override `@condition`: admit; broadcast WHERE additionally filtered by the condition.
* DELETE parity (Strand B inherits in full): non-override input-field `@condition` on a DELETE input → admit; WHERE = `pk = ? AND condition(?)`. Input-field `@condition(override: true)` on a DELETE input where PK is not in input → admit; WHERE = `condition(?)` only. Non-`@table` argument `@condition` on a DELETE → admit; WHERE = `pk = ? AND argCondition(?)`.

### Compilation

`mvn compile -pl :graphitron-sakila-example -Plocal-db` passes after `@value` removal. Add one sakila fixture exercising input-field-level non-override `@condition`: `UpdateFilmStatus` with a `status` field carrying `@condition` resolving to a method on a small `Conditions` class. Forces the compile tier to verify the reflection contract types check against real jOOQ generated classes.

### Execution

`DmlMutationsExecutionTest` / `DmlBulkMutationsExecutionTest` cover round trips. Default-path tests do not change (PK partition is structurally identical). Add:

* Bulk UPDATE with non-override input-field `@condition` against `film.release_year`. End-to-end proof of "PK + gate."
* Override `@condition` on an UnboundField (R215 wiring): round trip confirming the override predicate drives WHERE alone and the affected rows are exactly the predicate's matches.

### Unit

None.

## User documentation (first-client check)

* **Delete** `docs/manual/reference/directives/value.adoc`. Not cross-referenced from `directives/index.adoc` (the alphabetical and category lists at `docs/manual/reference/directives/index.adoc:12-38` do not mention it); no inbound xrefs.
* **Revise** `docs/manual/reference/directives/mutation.adoc`. The current prose at lines 65 and 120-123 describes the UPDATE partition in terms of `@lookupKey` (the user docs are already out of step with code under the `@value` regime; R188 brings the docs into alignment with the post-R188 PK-driven rule rather than the never-shipped `@value` doc shape). Replace those sections with:

[quote]
____
`UPDATE` partitions the `@table` input fields by primary-key membership: PK columns drive the WHERE clause (the row to update), non-PK columns drive the SET clause (the new values). The partition is read from the table catalog; no per-field directive is required.

For row-level gating or non-PK row identity, use xref:condition.adoc[`@condition`] on an input field or on a non-`@table` argument of the `@mutation` field. Non-override predicates AND with the PK WHERE; `override: true` on an input field replaces the PK WHERE with the predicate.

[source,graphql]
----
input FilmUpdateInput @table(name: "film") {
    filmId: ID!    @field(name: "film_id")
    rating: String @field(name: "rating")
    status: String @field(name: "status")
        @condition(condition: {className: "Conditions", method: "statusIs"})
}
----

When the WHERE side does not reduce to at most one row by construction (no PK in input, no override), set `multiRow: true` on the `@mutation` directive to acknowledge the broadcast intentionally.
____

* **Revise** `docs/manual/reference/directives/condition.adoc`. Add a "Use on `@mutation` fields" subsection naming the two placements (input field with or without `override:`, non-`@table` argument), the composition rule (non-override predicates AND in, single override replaces PK WHERE), and a link back to `mutation.adoc` for the UPDATE-specific story.

If the result does not read simply, the design is wrong; the drafts above are short and self-contained.

## Roadmap entries

* **R215 (`condition-override-on-unbound-field.md`):** R215 admits `@condition(override: true)` on UnboundField but the directive is silently dropped at emit. Strand B wires it through. R215 closes when R188 lands; capture in `changelog.md`.
* **R145 (UPSERT, `mutation-cardinality-safety-upsert.md`):** R145's conflict-key axis is unchanged. R188's contribution: (a) UPSERT's SET clause becomes "input columns not in the conflict-target column set" (PK-default rule generalised to conflict-key-default); (b) R145 inherits the two-placement `@condition` mechanism for layering predicates on top of the conflict-target match (e.g. tenant-scoped UPSERT). The R145 body edit lands in the same commit that drops `@value` (Strand A). No status change.
* **R146 (`mutation-cardinality-safety-unique-index.md`):** Stays Backlog. Under R188, "uniquely identified by an alternate unique key from input columns" without writing an override predicate no longer arises as a distinct shape. R146 reopens if a future schema needs that shape.
* **R222 (dimensional model pivot):** R188 contributes two slots (`overrideCondition`, `additionalConditions`) and a `multiRow` boolean to the UPDATE/DELETE field record. R222's `PredicateCarrier` slice picks them up directly; no sealed taxonomy on the field record to relocate. The structural rules (at-most-one-override, table-plus-scope-scalars reflection contract, no-op-override rejection) stay observable in the classifier so R222 has a stable contract to refactor against.
* **R130 / R144:** both shipped; R188 is the next iteration of the same partition story plus the `@condition`-on-mutations lift. Add a `changelog.md` entry on R188's Done: directive removal, the two-placement `@condition` admission, and the R215 closeout.

## Out of scope

* `@condition` on `@mutation` fields. The placements admitted (input field, non-`@table` argument) cover the known cases (per-row gate, override row-identity, filter token). Filed as a follow-up when a forcing case surfaces.
* `@condition` on input types (`INPUT_OBJECT` SDL location). Same reasoning. If "row-identity shared across mutations using the same input" turns out to be a real pattern, it lands as a separate item.
* Validating the `@condition` method's body (selectivity, uniqueness against catalog indexes). Author's responsibility; the `multiRow: true` opt-in and the `override: true` flag are the structural acknowledgements.
* INSERT is not restructured. INSERT has no WHERE clause; the mutation resolver rejects `@condition` on an INSERT input with "INSERT has no WHERE clause; `@condition` is not applicable."
* DELETE inherits Strand B in full (DELETE has a WHERE clause); the PK-default partition is N/A for DELETE (no SET). Schema migration and Tests cover DELETE alongside UPDATE.
