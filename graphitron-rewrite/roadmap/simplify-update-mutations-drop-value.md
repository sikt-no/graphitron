---
id: R188
title: "Replace @value with PK-default partition + @condition on mutations"
status: Spec
bucket: cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Replace @value with PK-default partition + @condition on mutations

UPDATE mutations currently partition `@table` input fields by an explicit `@value` directive: marked fields become the SET clause, unmarked fields become the WHERE clause (required to cover the PK unless `multiRow: true`). For the overwhelming common case, this partition is mechanical and the schema already implies it via PK metadata: PK columns identify the row (WHERE), non-PK columns carry the new values (SET). The `@value` directive, the paired structural diagnostics ("no @value fields", "every field is @value-marked"), and the load-bearing classifier check `mutation-input.update-set-fields-equal-value-marked` (see `MutationInputResolver.java:319-323`) all exist to police a partition the catalog already knows.

For the *interesting* case — non-PK row identity ("update where `email = X`") — `@condition` is the right mechanism, mirroring the query side. The current state on mutations is that `@condition` at the SDL parses, is structurally checked, but is *not wired through to mutation WHERE at all*: argument-level `@condition` is rejected outright (`MutationInputResolver.java:433`), input-field-level `@condition` is silently dropped after the `@value` co-occurrence check, and `MutationUpdateTableField` has no `ConditionFilter` slot to carry one (`MutationField.java:50-57`). R188 plumbs `@condition` through to mutation WHERE at four placements — input type, `@mutation` field, mutation argument, input field — composed via the existing query-side cascade rules, and pairs that with dropping `@value` and the PK-default partition. The two changes interlock: the partition rule, the override semantics, the PK-coverage check, and the SET-stability rule all reference each other and have to be designed together.

## Design

### Partition (`@table` input → SET / WHERE)

The SET / WHERE partition becomes purely PK-driven, read from the jOOQ catalog:

* Input fields whose target column is in the table's primary key contribute to `lookupKeyFields` (WHERE).
* Input fields whose target column is non-PK contribute to `setFields` (SET).
* `@condition` does not move fields between partitions. It contributes *predicates* to WHERE; it does not remove columns from SET. The "update where `email = X` but don't write `email`" case is expressed by *not putting `email` in the `@table` input* — argument-level `@condition` on a separate `filter: String!` arg of the `@mutation` field covers it cleanly (see below). Keeping the partition pure is what lets readers predict the SET set from the schema + catalog alone.

The PK-default partition replaces R144's `@value`-marked partition wholesale. INSERT and DELETE are untouched: INSERT has no partition (every input field is a VALUES cell), DELETE has no SET clause. Removing `@value` simplifies these arms too — the rejection clauses for "`@value` on DELETE / INSERT" disappear with the directive.

### `@condition` placements on mutations

`@condition` extends to four placements on the mutation surface, all composing via existing query-side cascade rules:

| Placement | Reflection contract | Use when |
|---|---|---|
| Input type (`input FilmUpdateInput @table(...) @condition(...)`) | Method takes `(Table t, <input-field-scalars>)`; same shape as query-side input-field `@condition` but reflected against the whole input's field set. Applies to every operation taking this input. | The row-identity story belongs to the row-spec; shared across multiple mutations on the same input. |
| `@mutation` field | Method takes `(Table t, <args from the mutation field>)`; mirrors query-side field-level `@condition`. | Per-operation override; one mutation needs a custom WHERE the other consumers of the same input shouldn't share. |
| Mutation argument | Method takes `(Table t, <that arg>)`; mirrors query-side argument-level `@condition`. Lifts the current rejection at `MutationInputResolver.java:433`. | WHERE inputs that don't belong in the row-spec: a search token, a tenant ID, a soft-delete flag. |
| Input field | Method takes `(Table t, <that leaf>)`; mirrors query-side input-field-level `@condition`. | Fine-grained per-column predicate: range checks, regex matches, tenant scoping per column. |

All present predicates AND together by default. `override: true` is layer-local and inherits the query-side cascade semantics already documented at `condition.adoc:7, 121-122`:

* Input-type `override: true` suppresses the implicit PK predicates derived from `@table` (the row-spec claims responsibility for row-identity).
* `@mutation`-field `override: true` suppresses the implicit PK predicates from the resolved `@table` input *and* the implicit `column = ?` for every direct argument on the mutation field. This broader sweep is the same scope the query-side field-level `override:` covers today (`condition.adoc:26`); R188 doesn't widen it, it just transports the existing rule to the mutation surface.
* Argument-level `override: true` suppresses just that argument's implicit `column = ?`.
* Input-field-level `override: true` suppresses just that input field's implicit predicate.

Inner explicit `@condition`s are *always preserved*, regardless of which outer layer overrides. The query-side rewrite already pins this against legacy semantics (`filmsOuterOverrideTableInput` is the regression-fence); the same fence applies on the mutation side.

**Partition vs predicate, made explicit.** The SET / WHERE partition is a *classifier output* read from PK metadata in the catalog. `@condition`'s predicates are *emitter inputs* AND-ed into WHERE. The two never share a column: a column is either in SET (non-PK input field) or in the implicit-PK WHERE (PK input field) or in a `@condition` method's parameter list (any input scalar reused as a predicate input). Stating the rule once here, rather than in each placement section, is what keeps the four layers tractable.

### Row identity (sealed sub-taxonomy)

R144's "WHERE must cover PK" generalises to "the field has a row-identity proof". R188 materialises that proof as a sealed sub-taxonomy on the UPDATE / DELETE field record, with three permits matching the three positive facts the emitter forks on:

* `RowIdentity.KeyLookup` — PK columns are present in the input as admissible carriers (R144's original case). Carries the implicit PK-column predicates.
* `RowIdentity.Predicate` — an `override: true` `@condition` at any layer has claimed responsibility for row-identity. Carries the composed override body; the implicit PK predicates are suppressed.
* `RowIdentity.BulkKeyLookup` — `multiRow: true` is set on `@mutation` (R144's broadcast opt-in preserved). No row-identity predicates; the bulk v-table path takes over.

The classifier produces *exactly one* `RowIdentity` variant per UPDATE/DELETE field; the validator's invariant is "this field has a `RowIdentity`"; the emitter switches exhaustively on the variant. The boolean-disjunction shape R144 carried ("any of three OR-branches must hold") is the wrong target for an invariant the emitter has to fork on — modelling the three facts as a sealed type is what lets the validator and emitter branch on the same identity rather than re-deriving the disjunction at emit time.

The naming is deliberate. The next iteration of the field model (R164, `field-model-two-axis-pivot`) introduces a `Filter` sealed family on `QueryBuilder.Update` with permits `KeyLookup | Predicate | BulkKeyLookup`. R188's `RowIdentity` is shaped to map one-to-one onto that family so R164's lift is a rename-plus-promotion (rename `RowIdentity` to `Filter`, hoist it from the field record to `QueryBuilder.Update.filter`), not structural rework. See "Roadmap entries" below for the coordination commitment.

`RowIdentity.Predicate` is *constructed from* the layered `WhereFilters` resolved against the four mutation placements (see Strand B); `WhereFilters` does not collapse away. The compact-constructor invariants on `WhereFilters` (the structural-rejection surface for "two overrides on overlapping scopes") are the load-bearing rejection layer, and they must survive the eventual lift into `Filter.Predicate` intact.

### Schema-legibility trade

Under `@value`, the SET/WHERE partition was visible from the SDL alone. Under R188, a reader looking at `FilmUpdateInput.title` cannot tell from the SDL alone that `title` will land in SET while `filmId` lands in WHERE; they must know that `filmId` is the PK in the `film` table. This is a real reduction in schema-local legibility, accepted in exchange for removing a directive whose information was already in the catalog and whose two structural diagnostics existed to police a partition the catalog already knew. The user-doc rewrite is the load-bearing mitigation: `mutation.adoc` names the inference rule once, so readers learn "PK → WHERE, non-PK → SET" alongside the rest of the UPDATE story. The trade is intentional, not incidental.

### PK-change consequence

Under `@value`, an author declared the partition explicitly, so a catalog migration that moved a column in or out of the PK left the SDL diagnostic-stable. Under R188, the same migration re-partitions every UPDATE input that includes the affected column at next build. The new behaviour is correct (the new PK *is* the new row identity), and it surfaces in the build rather than at runtime: any UPDATE input that the migration breaks (PK columns now absent from the input; non-PK columns now in the PK with no SET targets left) fails classification as `UnclassifiedField` via `mutation-input.where-identifies-row` or the empty-SET check. The change is silent in the SDL but loud in the build; "deliberate review" is forced by build failure, not an honour-system convention. No additional guard is added because the catalog/classifier already supply one.

UPSERT is deferred to R145; this spec adjusts R145's plan body so its conflict-target / SET partition uses the PK-default rule plus `@condition` cascade rather than re-introducing `@value`. See "Roadmap entries" below.

## Implementation

R188 has two strands: (A) remove `@value` and switch UPDATE's SET/WHERE partition to PK-default; (B) extend `@condition` to four mutation placements, plumb the resulting WHERE through to the emitter, and lift the row-identity check into the `RowIdentity` sealed sub-taxonomy. Strand B is the larger one. Implementer judgment governs commit structure: the strands can land together (the `RowIdentity` lift wants Strand B's override-flag plumbing to be present when the `KeyLookup` / `Predicate` arms are chosen) or as a short sequence (Strand A lands first under the existing conjunctive PK-coverage check; Strand B then reshapes the check into the `RowIdentity` sub-taxonomy). The coupling is narrower than "must land together": the genuine dependency is the `RowIdentity` lift on the override-flag readout, not the empty-SET diagnostic on `@condition` outputs (empty-SET is checkable from PK-set membership alone). Either order is acceptable; the seam exists.

### Strand A: drop `@value`, PK-default partition

* `graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls:220-231` — remove the `@value` directive declaration and its doc block.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:97` — remove `DIR_VALUE`. Audit imports across the rewrite tree.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilder.java:340` — remove the `assertDirective(ctx, DIR_VALUE)` registration line.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/DmlKind.java` — delete `acceptsValueMarker()` and its callers.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ArgumentRef.java` — rework `TableInputArg.of(...)` to drop the `valueMarkedNames` parameter and derive the partition from PK metadata read off `inputTable.tableName()` via `JooqCatalog`. The factory grows a `JooqCatalog` (or precomputed PK column set) argument; threading site is in `MutationInputResolver.resolveInput`.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java` — the `valueMarkedNames` parameter on `buildLookupBindings` flows out symmetrically: bindings derive WHERE-side from "PK columns in input", not from "input fields without `@value`". Refactor to read the PK set from the catalog rather than from a directive-marked name set.
* `MutationInputResolver.java`:
  - Drop the `valueMarkedNames` accumulation loop (lines 367-395) and the rejection sites it gates: `@value` on DELETE / INSERT, `@value` + `@condition` co-occurrence. The `@lookupKey` retirement check stays; rewrite its diagnostic to recommend `@condition` instead of `@value`.
  - Drop the UPDATE-specific structural diagnostics at lines 482-493 ("no `@value` fields to set", "every input field is `@value`-marked"). The new failure mode (PK in input, no non-PK columns) becomes a single structural check: if `setFields` is empty on UPDATE and no `override: true` `@condition` claims responsibility for the SET shape (it can't; `@condition` doesn't write), reject with "no non-PK columns to set; UPDATE has nothing to write".
  - Update the `LoadBearingClassifierCheck` annotation set: delete `mutation-input.update-set-fields-equal-value-marked`. The `mutation-input.where-columns-cover-pk` annotation renames to `mutation-input.where-identifies-row` and reshapes to the typed `RowIdentity` form (see strand B). Audit consumers — every `@DependsOnClassifierCheck(key = "mutation-input.update-set-fields-equal-value-marked", ...)` on `TypeFetcherGenerator`'s `reliesOn` clauses (around the bulk-UPDATE and bulk-UPSERT-prep blocks) needs the key reference removed. `LoadBearingGuaranteeAuditTest` will fail loudly on any orphan.
* `TypeFetcherGenerator.java` — no behavioural change to the SET/WHERE walk; the generator already walks `tia.setFields()` / `tia.lookupKeyFields()` and trusts the typed partition. The `reliesOn` JavaDoc comments referring to "`@value`-marked admissible carriers" need re-wording to "non-PK admissible carriers" (lines 1969, 1981-2000, 2061-2065, 2249-2284, 3741-3744). Mechanical.

### Strand B: `@condition` at four mutation placements

**Directive extension**

* `directives.graphqls` — extend `@condition`'s applicable locations from `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION` to add `INPUT_OBJECT`. Update the doc block to name the four placements and their reflection contracts (table + scope-appropriate values).
* `BuildContext.readConditionDirective(...)` — add an overload (or generalise) that reads `@condition` off a `GraphQLInputObjectType`. The existing implementation works element-agnostically for the directive payload; the SDL-location extension is the main lift.

**Resolver**

* `ConditionResolver.java` — add `resolveInputType(GraphQLInputObjectType inputType)` returning a sealed `InputTypeConditionResult` (None / Ok(ConditionFilter) / Rejected). Reflection binds the input type's field set against the method's parameter list via `ServiceCatalog.reflectTableMethod` with `TableSlotPolicy.REQUIRED`, mirroring `resolveField`. The existing `resolveField` and `resolveArg` paths stay unchanged — they're reused on the mutation side.
* `MutationInputResolver.java`:
  - In `resolveInput`, gather predicates from all four layers in order: input-type, `@mutation`-field, mutation-argument, input-field. The first three produce a `ConditionFilter` each (zero-or-one per layer); the fourth produces zero-or-more (one per `@condition`-bearing input field). Compose them into a typed `WhereFilters` record carried as a slot on `TableInputArg` (see model changes).
  - Lift the argument-level rejection at line 433: argument-level `@condition` on `@mutation` is now admitted on non-`@table` args. `@condition` on the `@table` arg itself stays rejected (the input-type-level placement is the right home for "this input's row identity").
  - Apply the override cascade: track an `enclosingOverride` flag analogous to `FieldBuilder.projectFilters:1318-1328` and suppress implicit PK / implicit `column = ?` predicates per the layer-local rule from the Design section.
  - Resolve the `RowIdentity` variant from (a) PK-set-vs-input-columns membership, (b) any-layer-`override:true`-in-`WhereFilters`, (c) `@mutation(multiRow: true)`. The three branches are mutually exclusive in priority order: `override:true` at any layer → `RowIdentity.Predicate` (carrying the composed `WhereFilters`); else `multiRow: true` → `RowIdentity.BulkKeyLookup`; else PK columns present in input → `RowIdentity.KeyLookup` (carrying the implicit PK-column predicates); else reject via `mutation-input.where-identifies-row`. The priority ordering is the disjunction made deterministic: an author who writes `override:true` *and* `multiRow:true` gets `Predicate` (the more specific signal), not silent precedence ambiguity.

**Model**

Two sealed sub-taxonomies land: `WhereFilters` (the layered construction-side input) on `TableInputArg`, and `RowIdentity` (the resolved row-identity proof) on the UPDATE / DELETE field record. The two are not redundant: `WhereFilters` carries the layer-of-origin structure and the structural-rejection invariants; `RowIdentity` carries the resolved fork the emitter switches on. The validator's invariant lives on `WhereFilters`; the emitter's dispatch lives on `RowIdentity`.

* `ArgumentRef.java` — add a `whereFilters` slot on `TableInputArg`, typed as a sealed sub-taxonomy `WhereFilters` carrying layer-of-origin rather than as a flat `List<ConditionFilter>`:

  - `WhereFilters` is a sealed record family with four arms keyed by layer of origin: `InputTypeLayer`, `MutationFieldLayer`, `ArgLayer`, `InputFieldLayer`. Each arm carries its layer's `ConditionFilter` and the `override` flag directly; arm identity replaces a separate layer-tag. The container record carries `Optional<InputTypeLayer>`, `Optional<MutationFieldLayer>`, `List<ArgLayer>`, `List<InputFieldLayer>`.
  - Compact constructor expresses the structural-reject rules from the Design section as invariants: see "Mutual exclusion and structural checks" below for the chain rule the constructor enforces.
  - `WhereFilters` is the *construction-side input* to `RowIdentity.Predicate`. The composition (input-type → mutation-field → arg → input-field, AND-ed together) is done by `RowIdentity.Predicate`'s factory; `WhereFilters` itself doesn't compose, it just structures the layered input. The layering survives R164's lift into `Filter.Predicate` intact: it's R188's load-bearing rejection surface (`mutation-condition.where-filters-well-formed`).

* `MutationField.java` — `MutationUpdateTableField` and `MutationDeleteTableField` gain a `RowIdentity identity` slot. `MutationDmlRecordField` follows the same shape on its DELETE-equivalent path. `MutationUpsertTableField` gets the same slot prospectively for R145's benefit (R145's UPSERT conflict-target is a different axis, but tenant-scoping and similar predicates layered on top of an UPSERT route through the same `RowIdentity.Predicate` mechanism). The slot is set by `MutationInputResolver.resolveInput` per the priority-ordered selection above; no field record computes the variant from `whereFilters()` + flags at emit time.

  Why the slot lives on the field record rather than on `TableInputArg`: the validator (`MutationInputResolver`) and the emitter (`TypeFetcherGenerator`'s UPDATE / DELETE arms) both fork on row-identity, which by *Generation-thinking* belongs in the model the consumers read. The field record is the consumer-facing model; the arg's `whereFilters` is the construction-side detail.

**Emitter**

* `TypeFetcherGenerator.java` — UPDATE and DELETE arms (single-row + bulk + payload-returning variants) switch exhaustively on `field.identity()`:
  - `RowIdentity.KeyLookup` → existing emission path: build WHERE from `lookupKeyFields` ColumnField bindings as `.where(pk_col.eq(...))` AND-ed across PK columns. Unchanged from R144.
  - `RowIdentity.Predicate` → emit `.where(<composed-filter>)` using the `WhereFilters` payload reached via `tableInputArg().whereFilters()`. The emitter walks the four layer arms in fixed order (input-type, mutation-field, arg, input-field) and AND-s the per-layer `ConditionFilter`s together. Implicit PK predicates are *not* emitted under this arm — the variant identity is the suppression signal, not a separate flag read. Threading remains the same as today: each `ConditionFilter`'s reflected `MethodRef.Param` extractions resolve against the call-site `Map`. For the bulk arm, the filters operate over the v-table reference (matching query-side bulk-condition emission).
  - `RowIdentity.BulkKeyLookup` → no row-identity predicates; emission delegates to the existing bulk v-table path which already does broadcast updates with no WHERE.
* The pre-revision shape that read `field.whereFilters()` plus an override flag plus `multiRow` was the predicate-over-pre-resolved-data smell; `RowIdentity` arm identity replaces it. The validator decides the variant once at classify time and the emitter never re-derives the decision.
* Reflection-shape note: the method takes the surrounding `@table`'s jOOQ table reference plus the scoped scalars. This matches the v-table-safe parameter shape pinned by `mutation-condition.method-shape-table-plus-scope-scalars` (see below) — the bulk arm requires it because per-row jOOQ records have no v-table analogue. The "per-row record as method parameter" idea floated during spec drafting is rejected for this reason. Query-side leaf `@condition` may still receive a carrier record via different paths; the mutation-side constraint is narrower because it must work under bulk emit.

**Mutual exclusion and structural checks**

These rules express as compact-constructor invariants on `WhereFilters`, so the classifier produces a structurally well-formed layered filter set or fails loudly. A `Resolved.Rejected` carries the structural reason; the emitter never sees a malformed layer combination.

The four placements form a containment chain: input-type ⊋ mutation-field ⊋ arg ⊋ input-field (mirroring `argument-resolution.adoc`'s downward-cascade story on the query side). Override-conflict is then a single rule, not a pairwise enumeration:

* **Chain rule.** At most one `override: true` may be present across the four layers. Two layers both carrying `override: true` is rejected regardless of which pair (the chain order means any two layers have overlapping scope under the broader layer's sweep, so "two scopes claiming the same suppression" is the universal failure mode). The diagnostic names the layers in conflict and the chain position of each.

Other structural rejections, not subsumed by the chain rule:

* Argument-level `@condition` on the `@table` input arg is rejected, with the diagnostic "argument-level `@condition` on the `@table` input arg is rejected; use input-type `@condition` instead — that's the right scope for this input's row-identity story". The diagnostic names the migration path so the SDL author isn't left guessing.
* Input-field-level `@condition` carrying `override: true` on a *non-PK* input field is rejected as no-op-override: a non-PK input field has no implicit predicate to suppress (it's a SET contribution; the partition is pure per the Design section). The diagnostic reads "override: true on input field '<name>' is a no-op (non-PK fields have no implicit predicate; remove the override flag or move the @condition to a layer with broader scope)".
* `@mutation`-field `@condition` co-occurring with the chain rule covers two-scope overrides; on co-occurrence *without* override, all explicit `@condition`s are AND-ed (no conflict, no rejection). Inner explicit `@condition` annotations are *always preserved*, regardless of which outer layer overrides — the `filmsOuterOverrideTableInput` regression-fence applies on the mutation side too.

**Load-bearing classifier checks**

The reshape of `mutation-input.where-columns-cover-pk` from "PK columns are in the input" to "the field has a `RowIdentity` variant" is itself a load-bearing change. The check renames to `mutation-input.where-identifies-row` and its description states the typed promise: *the field's `identity()` slot carries one of `RowIdentity.{KeyLookup, Predicate, BulkKeyLookup}`; UnclassifiedField otherwise.* The validator produces a variant, not a boolean; the load-bearing key promises the variant exists, not that a disjunction was true. Every `@DependsOnClassifierCheck(key = "mutation-input.where-columns-cover-pk", ...)` consumer needs re-verification — the consumer now reads a typed slot and switches exhaustively, rather than asserting a boolean and then re-deriving the branch.

Three new `@LoadBearingClassifierCheck` keys land in `MutationInputResolver`:

* `mutation-condition.method-shape-table-plus-scope-scalars` — pins the reflection contract: every `@condition` method on a mutation takes `(Table t, <scope-appropriate scalars>)`, never a per-row jOOQ record. The bulk-emit path depends on this for v-table compatibility; the validator must guarantee it.
* `mutation-condition.where-filters-well-formed` — pins the `WhereFilters` invariant set: at most one input-type layer, at most one mutation-field layer, override chain rule (at most one `override: true` across layers), no no-op overrides on non-PK input fields. The emitter walks `whereFilters` without re-checking; the validator owns the structural promise.
* `mutation-update.row-identity-matches-emit-arm` — pins the `RowIdentity` variant's correspondence with the emitter's exhaustive switch. The promise: if the field has `RowIdentity.KeyLookup`, the field also has non-empty `lookupKeyFields`; if `RowIdentity.Predicate`, the field's `tableInputArg().whereFilters()` is non-empty and contains an `override: true` layer (or the variant is unreachable); if `RowIdentity.BulkKeyLookup`, the field's `@mutation` carries `multiRow: true`. The emitter consumes the variant identity directly; this check guarantees the variant identity matches the payload the consumer reads.

## Schema migration

* `graphitron-rewrite/graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:1194-1205` — strip `@value` from `FilmUpdateInput.title` and `FilmUpdateInput.description`; rewrite the comment block to describe PK-driven inference. No structural change to the input itself: `filmId` is still PK and still becomes WHERE; `title` and `description` are still non-PK and still become SET.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` — strip `@value` from every embedded SDL snippet (the `grep` above lists 14 sites). The cases that exercised the deleted diagnostics convert as follows:
  - "UPDATE without any `@value` fields → UnclassifiedField" (lines 5577-5586) becomes "UPDATE where the input contains only PK columns → UnclassifiedField with 'no non-PK columns to set'".
  - "UPDATE where every input field is `@value`-marked → UnclassifiedField" (lines 5590-...) becomes "UPDATE where the input contains no PK columns, no `@condition`, and `multiRow:` is absent → UnclassifiedField via PK-coverage failure". (With `multiRow: true` the same shape admits as a broadcast UPDATE.)
  - "`@value` on DELETE → UnclassifiedField" and "`@value` + `@condition` mutually exclusive" (lines 6106-6134) delete outright; under R188 these aren't possible.
  - The TableInputArg projection test (line 5681) keeps its assertion shape: `setFields` in declaration order, but the partition source is PK-vs-non-PK instead of `@value`.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/SingleRecordPayloadPipelineTest.java:377-382, 610-612` — strip `@value` from the UPDATE and UPSERT cases. UPSERT cases continue to use UPSERT-rejection diagnostic until R145 lands.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java:446, 447, 493, 494` — strip `@value`.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/MutationDmlNodeIdClassificationTest.java:100-103, 148, 176` — strip `@value` and adjust the assertion at line 103 (the "no `@value` fields to set" diagnostic) to the new "no non-PK columns to set" wording. Cases at 148 / 176 lose the `@value` keyword and keep working unchanged (`name` is non-PK).

## Tests

The four-tier discipline (unit / pipeline / compilation / execution; see `rewrite-design-principles.adoc:126-140`) governs which tier adds coverage.

### Pipeline (primary)

Strand A updates:

* `GraphitronSchemaBuilderTest` cases listed under "Schema migration" above adjust to the new diagnostics and the PK-default partition.
* Add a "broadcast shape" admit case: UPDATE input with no PK columns and no `@condition`, with `multiRow: true` → `MutationUpdateTableField` (admit). Pins down that R188 preserves R144's `multiRow:` semantics rather than retightening them.
* Add an "empty SET" rejection case: UPDATE input with only PK columns → UnclassifiedField with "no non-PK columns to set".

Strand B coverage (the new placements). Each admit case asserts the resolved `RowIdentity` variant on the field record, not just admit/reject; the variant assertion is the load-bearing piece that `mutation-update.row-identity-matches-emit-arm` pins:

* Input-type `@condition`, `override: false`, PK in input: admit with `RowIdentity.KeyLookup`; WHERE = `(PK auto-eq) AND (condition)`. Single-row and bulk variants.
* Input-type `@condition`, `override: true`, PK *not* in input, no `multiRow:`: admit with `RowIdentity.Predicate`; WHERE = `(condition)`; the row-identity proof is the override layer.
* Input-type `@condition`, `override: true`, PK in input: admit with `RowIdentity.Predicate`; implicit PK predicates suppressed by variant identity; SET still contains every non-PK column (the override changes WHERE, not the partition).
* `@mutation`-field `@condition`, no override: admit with `RowIdentity.KeyLookup`; the field-level condition AND-s into WHERE alongside the implicit PK predicates.
* `@mutation`-field `@condition`, `override: true`: admit with `RowIdentity.Predicate`; preserves any inner explicit `@condition` (regression-fence mirroring `filmsOuterOverrideTableInput`).
* Argument-level `@condition` on a `@mutation` field with a non-`@table` arg (e.g. `filter: String! @condition(...)`): admit; lifts the line-433 rejection. The non-table-arg shape doesn't participate in the partition; it only adds a WHERE predicate AND-ed alongside the variant's existing payload.
* Argument-level `@condition` on the `@table` arg of a `@mutation` field: reject ("`@condition` on the `@table` input arg: put it on the input type instead").
* Input-field-level `@condition`: admit with the per-field predicate AND-ed into WHERE; PK field with `@condition` keeps its implicit predicate (cascade) and variant stays `RowIdentity.KeyLookup`; non-PK field with `@condition` does *not* move out of SET (the partition is pure; the predicate is additive).
* Input-field-level `@condition` with `override: true` on a non-PK field: reject ("override: true on input field '<name>' is a no-op (non-PK fields have no implicit predicate)"); covers the no-op-override structural rule.
* Chain-rule violation: any two layers in the input-type ⊋ mutation-field ⊋ arg ⊋ input-field chain both carrying `override: true` → reject (structural). At minimum: (input-type + input-field), (input-type + mutation-field), (mutation-field + arg), (mutation-field + input-field), (arg + input-field). The full pairwise matrix exercises the chain rule directly rather than enumerating instances.
* `multiRow: true` cases: PK *not* in input, no `@condition`, `multiRow: true` → admit with `RowIdentity.BulkKeyLookup`. With `override: true` `@condition` *and* `multiRow: true`, the priority rule selects `RowIdentity.Predicate` (more specific signal wins); test pins this.

### Compilation

The sakila-example `updateFilm` / `updateFilms` / `updateFilmsPayload` mutations stay structurally identical (same PK partition, same SET columns); `mvn compile -pl :graphitron-sakila-example -Plocal-db` passes with no further change beyond `@value` removal.

Add one sakila-example fixture exercising input-type `@condition`: an `UpdateFilmByTitle` input bound to `film` with `title` as a non-PK input field and an input-type `@condition` resolving to a reflected method on a small `Conditions` class. This forces the compile tier to verify the reflection contract types check (the method's parameter list against the input type's field set) against real jOOQ generated classes.

### Execution

`DmlMutationsExecutionTest` / `DmlBulkMutationsExecutionTest` cover the single-row and bulk-UPDATE round trips. The default-path tests don't need changes (the PK partition is structurally identical). Add two execution cases:

* `@condition`-with-`multiRow:` predicate-driven UPDATE against `film.release_year` (mirror the R144 `deleteFilmsByReleaseYear` execution fixture but in UPDATE form). End-to-end proof of the "non-PK WHERE" story.
* Input-type `@condition` with `override: false`: confirms that an authored predicate AND-s with the PK auto-equality and the resulting SQL touches only the intended row.

### Unit

No new unit tests. The deleted `LoadBearingClassifierCheck` annotation removes one fixture from the classifier-check audit; verify nothing depends on its key.

## User documentation (first-client check)

R188's user-facing doc surface: one page deleted, two pages revised.

* **Delete** `docs/manual/reference/directives/value.adoc`. The page is not cross-referenced from `directives/index.adoc` (the alphabetical and category lists at `docs/manual/reference/directives/index.adoc:12-38` don't mention it); no inbound xrefs resolve to it. Clean deletion.
* **Revise** `docs/manual/reference/directives/mutation.adoc`. The page's UPDATE section (lines 65, 120-123) still describes the pre-R144 `@lookupKey`-based partition. Draft of the replacement prose:

[quote]
____
`UPDATE` partitions the `@table` input fields by primary-key membership: PK columns drive the WHERE clause (the row to update), non-PK columns drive the SET clause (the new values). The partition is read from the table catalog; no per-field directive is required.

For row identity that doesn't follow the PK, use xref:condition.adoc[`@condition`]. The most common shape is one `@condition` on the input type, declaring "rows of this row-spec are identified this way":

[source,graphql]
----
input FilmUpdateByTitle @table(name: "film") @condition(condition: {className: "...", method: "filmHasTitle"}, override: true) {
    title:  String! @field(name: "title")
    rating: String  @field(name: "rating")
}
----

`@condition` is also valid on the `@mutation` field (per-operation override), on a non-`@table` argument of the `@mutation` field (a separate filter token), and on individual input fields (per-column predicate). All four layers compose via the standard `@condition` cascade.

When the WHERE side does not reduce to at most one row by construction (no PK in the input, no `override: true` `@condition`), set `multiRow: true` on the `@mutation` directive to acknowledge the broadcast intentionally.
____

* **Revise** `docs/manual/reference/directives/condition.adoc`. The directive's applicable-locations list extends to `INPUT_OBJECT`; the per-element "what predicates does this see" rows in the constraints section grow a row for input-type-level `@condition`. The override cascade table grows a row for the input-type layer. Add a short subsection "Use on `@mutation` fields" that names the four placements, mirrors the table from the Design section here, and links back to `mutation.adoc` for the UPDATE-specific story.

The two revised pages stay short. If the result doesn't read simply, the design is wrong; the drafts above are short and self-contained, so the bar passes.

## Roadmap entries

* **R164 (field model pivot, `field-model-two-axis-pivot.md`, Backlog):** R164 pivots the field model into three dimensions and introduces a `Filter` sealed family on `QueryBuilder.Update` with permits `KeyLookup | Predicate | BulkKeyLookup`. R188 lands ahead of R164 and is shaped for forward-compatibility: R188's `RowIdentity` sub-taxonomy on the UPDATE / DELETE field record uses the same permit names and the same three-branch semantics. R164's lift on R188's contributions is mechanical — rename `RowIdentity` to `Filter`, hoist it from the field record to `QueryBuilder.Update.filter`, route the existing `WhereFilters` through `Filter.Predicate` as the construction-side input. R188 does *not* preempt R164: the slot lives on the field record (not on `QueryBuilder.Update`, which doesn't exist yet) and the type is named `RowIdentity` (not `Filter`) so R164's contribution is the visible rename-plus-promotion the R164 plan body advertises. R164's note in its `Dependencies and sequencing` section captures the corresponding R164-side commitment (preserve `WhereFilters` as construction-side input; preserve the four-placement composition into `Filter.Predicate`; do not collapse `WhereFilters`'s structural-rejection invariants).
* **R145 (UPSERT, `mutation-cardinality-safety-upsert.md`):** R145's design fork is named-unique-key-driven (`conflictKey:` selects the conflict target, PK by default but any alternative unique index by name). That axis is *not* a subset of R188's "PK-default + `@condition` predicates": UPSERT's `ON CONFLICT (cols)` requires concrete columns from the input row, and `@condition` (an arbitrary predicate) is the wrong escape hatch for the conflict target. R188's contributions to R145: (a) drops `@value` from its partition description; SET clause becomes "input columns not in the conflict-target column set" (PK-default partition rule, generalised to "conflict-key-default"). (b) R145 inherits the four-placement `@condition` mechanism for layering predicates *on top of* the conflict-target match (e.g. tenant-scoped UPSERT). The `conflictKey:` selection and any named-unique-key shape stay R145's responsibility. The R145 body edit lands in the same commit that drops `@value` (Strand A) — R145 currently reads "The `@value` partition extends naturally from R144", which becomes stale the moment Strand A ships and must not be left dangling. No change to R145's status (still Backlog).
* **R146 (`mutation-cardinality-safety-unique-index.md`):** R188 doesn't relax the unique-index story; it relocates the question. Under R188, the WHERE side either covers PK (`RowIdentity.KeyLookup`) or is driven by `@condition` (`RowIdentity.Predicate`, any layer override) or is broadcast (`RowIdentity.BulkKeyLookup`); a non-PK unique-key WHERE driven *purely by input columns* (no `@condition`) no longer arises as a distinct shape. R146 stays Backlog with an addendum: if a future schema wants "uniquely identified by an alternate unique key from input columns" without writing a `@condition` method, R146 reopens.
* **R130 / R144:** both shipped; R188 is the next iteration of the same partition story plus the `@condition`-on-mutations lift. Add a `changelog.md` entry on R188's Done capturing the directive removal, the four-placement `@condition` admission, *and* the `RowIdentity` sealed sub-taxonomy as milestones worth keeping in the historical record. Cross-reference R164 from the changelog entry so a future R164 implementer finds the R188 coordination thread.

## Out of scope

* Lifting `@condition` to additional element types beyond `INPUT_OBJECT` (e.g. `OBJECT_TYPE` for output-side filtering on `@table` types). R188 only extends the directive to the input side.
* Validating the `@condition` method's body — its selectivity, its relationship to unique indexes, whether it really matches at-most-one row. The author's responsibility, exactly as on the query side. The `multiRow: true` opt-in and the `override: true` cascade are the structural acknowledgements; the runtime semantics are the author's.
* INSERT is not restructured. INSERT has no WHERE clause to attach `@condition` to. The directive's location extension is permitted on `INPUT_OBJECT` at the SDL level; the mutation resolver rejects input-type `@condition` on an INSERT-only input with the diagnostic "INSERT has no WHERE clause; @condition is not applicable on an INSERT input". The rejection is permanent — no future roadmap item is planned that would admit it. (R145's UPSERT does have an `ON CONFLICT` predicate target, but that's the conflict-key axis, not `@condition`; see R145 above for the UPSERT-specific composition.)
* DELETE *is* restructured to the extent that it inherits the four-placement `@condition` lift (DELETE has a WHERE clause and benefits from the same row-identity story). The PK-default partition doesn't apply to DELETE — DELETE has no SET — but the `RowIdentity` sub-taxonomy does, and the cascade works the same way. The Schema-migration and Tests sections cover DELETE alongside UPDATE; INSERT is untouched.
