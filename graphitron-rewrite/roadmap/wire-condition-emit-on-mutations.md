---
id: R245
title: "Wire @condition through to mutation WHERE (emit half + new placements)"
status: Backlog
bucket: cleanup
depends-on: [simplify-update-mutations-drop-value]
created: 2026-05-27
last-updated: 2026-05-27
---

# Wire @condition through to mutation WHERE (emit half + new placements)

`@condition` on mutations is half-built today: `MutationInputResolver.java` admits input-field-level `@condition(override: true)` (R215, lines ~482-498) but the directive is a no-op at emit (no `.where(...)` clause is produced). Argument-level `@condition` on a non-`@table` mutation argument is rejected outright (line 446). Input-field-level `@condition` without `override:` is rejected. This item closes the emit half and lifts the two admission rejections so the directive does something useful.

Was Strand B of R188 (split out so R188 can land as a focused `@value` removal). The PK-default partition R188 ships is the baseline this item layers `@condition` predicates on top of.

## Design

### Placements

Three placements admitted:

| Placement | Effect | Use when |
|---|---|---|
| Input field, no `override:` | Predicate AND-s into WHERE alongside the implicit PK predicates. | Per-row gate: "update by ID, but only if status matches." |
| Input field, `override: true` | Predicate replaces the implicit PK WHERE. | Row identified by something other than PK. R215 wiring. |
| Non-`@table` mutation argument | Predicate AND-s into WHERE, shared across all rows in a bulk call. | Per-call filter token: tenant ID, search term, soft-delete flag. |

Explicitly not admitted:

* `@condition` on the `@mutation` field itself. No forcing function; reachable from the placements above.
* `@condition` on the input type (`INPUT_OBJECT` SDL location is not added to the directive). No forcing function.
* `@condition` on the `@table` arg of a `@mutation` field. Same rejection as today; diagnostic recommends an input field or non-`@table` argument instead.

If either omitted placement turns out to be painful, file a follow-up with a concrete forcing case attached.

### Composition

All non-override `@condition` predicates AND together. At most one input-field-level `@condition(override: true)` is permitted per input (two overrides on different fields each claim the row-spec is ambiguous; reject).

When an override is present, it replaces the implicit PK WHERE; non-override predicates still AND in. When no override is present, the implicit PK predicates drive WHERE and the non-override predicates AND alongside. Inner explicit `@condition`s are always preserved regardless of override (the `filmsOuterOverrideTableInput` regression-fence applies on the mutation side).

`@condition` does not move fields between R188's SET/WHERE partition. It contributes predicates AND-ed into WHERE; it does not remove columns from SET.

### Row-identity disjunction (R144 update)

The R144 check stays a disjunction: the field has a row-identity proof iff at least one of

* PK columns are covered by input fields, OR
* an input-field `@condition(override: true)` is present, OR
* `multiRow: true` is set on the `@mutation` directive

holds; else the field is `UnclassifiedField` via `mutation-input.where-identifies-row`. No sealed `RowIdentity` taxonomy; the disjunction is checked directly in `MutationInputResolver`, and the emitter forks once on the three cases.

R188 ships with the override-true case present-but-no-emit; this item makes it emit, so the disjunction's middle arm becomes load-bearing rather than just an admission.

## Implementation

### Admission and resolution

* `MutationInputResolver.java`:
  * Lift the rejection at lines ~438-440 (`if (foundTia.argCondition().isPresent()) { return ...rejection... }`): argument-level `@condition` on a non-`@table` argument of a `@mutation` field is admitted. `@condition` on the `@table` arg stays rejected with a diagnostic recommending an input field or non-`@table` argument.
  * Admit input-field-level `@condition` without `override:` (today only `override: true` admits, per `MutationInputResolver.java:482-498`). Both override and non-override forms now compose into the field record.
* `ConditionResolver.resolveArg(...)` is already in place; no new resolver surface required.

### Model

`MutationField.java`: `MutationUpdateTableField`, `MutationDeleteTableField`, and `MutationDmlRecordField`'s DELETE-equivalent path gain two slots:

* `Optional<ConditionFilter> overrideCondition`: the composed override predicate (at most one input-field-level `@condition(override: true)`, plus any inner non-override `@condition`s AND-ed for the regression-fence). Absent when no input field carries `override: true`.
* `List<ConditionFilter> additionalConditions`: non-override `@condition`s from input fields and non-`@table` arguments, in source order.

`multiRow:` stays on `@mutation` as today. `MutationUpsertTableField` gets the same two slots prospectively for R145's tenant-scoping use; UPSERT's conflict target is a separate axis.

The composition step in `MutationInputResolver.resolveInput` reads input-field-level `@condition` annotations off `InputField.condition()`, reads each non-`@table` argument's directive via `ConditionResolver.resolveArg`, applies the at-most-one-override rule, and writes the two slots.

### Emitter cascade

`TypeFetcherGenerator.java` UPDATE and DELETE arms (single-row, bulk, and payload-returning variants) fork once on slot state:

```
if (overrideCondition.isPresent())  → .where(override AND additionalConditions...)
else if (multiRow)                  → no WHERE; additionalConditions, if any, AND-ed in
else                                → .where(implicitPk AND additionalConditions...)
```

Threading: each `ConditionFilter`'s reflected `MethodRef.Param` extractions resolve against the call-site `Map` exactly as today's argument-level `@condition` does. For the bulk arm, the filter operates over the v-table reference, matching query-side bulk-condition emission. The emitter never re-derives the disjunction; the slots are pre-resolved at classify time.

### Reflection-shape invariant

Each mutation-side `@condition` method takes `(Table t, <scope-appropriate scalars>)`: a subset of the input's field set, or the surrounding argument's value. Never a per-row jOOQ record; the bulk-emit path has no v-table analogue. `argMapping:` is supported (rebind GraphQL field names to differently-named Java parameters), mirroring query-side leaf `@condition`. Enforced at reflection time in `ConditionResolver`, diagnostic key `mutation-condition.method-shape-table-plus-scope-scalars`.

This narrows the mutation-side reflection contract against the query-side one (which permits row-record parameters via different paths). The uniform narrowing across single-row and bulk mutations is a deliberate choice; mixing per-row-record and v-table shapes inside one directive would require classify-time bulk awareness the resolver doesn't have today.

### Structural rules

Applied at classify time in `MutationInputResolver`:

* **At most one input-field-level `override: true` per input.** Two overrides on different fields each claim row-identity responsibility; reject with a diagnostic naming the conflicting fields.
* **`@condition` on the `@table` input arg stays rejected.** Diagnostic: "argument-level `@condition` on the `@table` input arg is rejected; put it on a non-`@table` argument or on an input field of the `@table` input."
* **Inner explicit `@condition`s are always preserved.** The `filmsOuterOverrideTableInput` regression-fence applies on the mutation side; the composition step AND-s inner non-override filters into the override slot when override is present.

## Tests

Pipeline (each admit case asserts the slot values on the field record, not just admit/reject):

* Input-field `@condition`, no `override:`, PK in input: admit; WHERE = `pk = ? AND condition(?)`. Single-row and bulk variants.
* Input-field `@condition(override: true)`, PK NOT in input: admit; WHERE = `condition(?)` only. Override slot populated; `additionalConditions` empty.
* Input-field `@condition(override: true)`, PK in input: admit; WHERE = `condition(?)`. Override suppresses implicit PK. SET still contains every non-PK column.
* Non-`@table` argument `@condition`: admit; WHERE = `pk = ? AND argCondition(?)`. Lifts the line-446 rejection.
* `@condition` on the `@table` arg: reject with the migration-pointing diagnostic.
* Two input fields with `@condition(override: true)`: reject (at-most-one-override).
* Mixed layers: one input field with `override: true` plus one input field with non-override `@condition`: admit; override populates the override slot, non-override populates `additionalConditions`, both compose into WHERE.
* `multiRow: true` plus non-override `@condition`: admit; broadcast WHERE additionally filtered by the condition.
* DELETE parity: non-override input-field `@condition` on a DELETE input → admit; WHERE = `pk = ? AND condition(?)`. Input-field `@condition(override: true)` on a DELETE input where PK is not in input → admit; WHERE = `condition(?)` only. Non-`@table` argument `@condition` on a DELETE → admit; WHERE = `pk = ? AND argCondition(?)`.
* Reflection-shape asymmetry: a `@condition` method whose signature includes a per-row jOOQ record parameter passes the query-side reflection contract but is rejected on the mutation side with `mutation-condition.method-shape-table-plus-scope-scalars`.

Compilation: add one sakila fixture exercising input-field-level non-override `@condition`: e.g. `UpdateFilmStatus` with a `status` field carrying `@condition` resolving to a method on a small `Conditions` class. Forces the compile tier to verify the reflection contract types check against real jOOQ generated classes.

Execution: add to `DmlMutationsExecutionTest` / `DmlBulkMutationsExecutionTest`:

* Bulk UPDATE with non-override input-field `@condition` against `film.release_year`. End-to-end proof of "PK + gate."
* Override `@condition` on an UnboundField (R215 wiring closeout): round trip confirming the override predicate drives WHERE alone and the affected rows are exactly the predicate's matches.

## User documentation

* **Revise** `docs/manual/reference/directives/condition.adoc`. Add a "Use on `@mutation` fields" subsection naming the two placements (input field with or without `override:`, non-`@table` argument), the composition rule (non-override predicates AND in, single override replaces PK WHERE), and a link to `mutation.adoc` for the UPDATE-specific story.
* **Cross-link from** `docs/manual/reference/directives/mutation.adoc` (R188 already mentions `@condition` as the escape hatch for non-PK row identity; this item makes that statement true at emit).

## Roadmap entries

* **R215** (already shipped, admit-but-no-emit half): on this item Done, append a follow-on note to R215's `changelog.md` entry recording the emit-side closeout.
* **R145** (UPSERT): R145 inherits the two-placement `@condition` mechanism for layering predicates on top of the conflict-target match (tenant-scoped UPSERT).
* **R222** (dimensional model pivot): the two slots (`overrideCondition`, `additionalConditions`) on the UPDATE/DELETE field record are R222's `PredicateCarrier` slice fodder; no sealed taxonomy to relocate.

## Out of scope

* `@condition` on `@mutation` fields directly. The admitted placements cover known cases.
* `@condition` on `INPUT_OBJECT` SDL location. Same reasoning.
* Validating the `@condition` method's body (selectivity, uniqueness against catalog indexes). Author's responsibility; `multiRow: true` and `override: true` are the structural acknowledgements.
* INSERT. INSERT has no WHERE clause; the mutation resolver rejects `@condition` on an INSERT input with "INSERT has no WHERE clause; `@condition` is not applicable."
