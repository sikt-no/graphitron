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

### PK-coverage check (disjunction)

R144's "WHERE must cover PK" is satisfied when *any* of:

* PK columns are present in the input as admissible carriers (the current rule), OR
* An `override: true` `@condition` exists at any layer (the author has taken responsibility for row-identity), OR
* `multiRow: true` is set on `@mutation` (broadcast opt-in; R144's semantics preserved).

R144's `multiRow:` semantics are preserved as-is: `multiRow: true` is the opt-out for the PK-coverage check, regardless of whether `@condition` is present. The "naked broadcast UPDATE" shape (no PK in input, no `@condition`, `multiRow: true`) stays expressible.

### Schema-legibility trade

Under `@value`, the SET/WHERE partition was visible from the SDL alone. Under R188, a reader looking at `FilmUpdateInput.title` cannot tell from the SDL alone that `title` will land in SET while `filmId` lands in WHERE; they must know that `filmId` is the PK in the `film` table. This is a real reduction in schema-local legibility, accepted in exchange for removing a directive whose information was already in the catalog and whose two structural diagnostics existed to police a partition the catalog already knew. The user-doc rewrite is the load-bearing mitigation: `mutation.adoc` names the inference rule once, so readers learn "PK → WHERE, non-PK → SET" alongside the rest of the UPDATE story. The trade is intentional, not incidental.

### PK-change consequence

Under `@value`, an author declared the partition explicitly, so a catalog migration that moved a column in or out of the PK left the SDL diagnostic-stable. Under R188, the same migration silently re-partitions every UPDATE input that includes the affected column at next build. The new behaviour is correct (the new PK *is* the new row identity), but it happens without any SDL change. This is an intended consequence of catalog-driven inference; the design assumes PK migrations are rare and accompanied by a deliberate review of mutation surfaces. No additional guard is added.

UPSERT is deferred to R145; this spec adjusts R145's plan body so its conflict-target / SET partition uses the PK-default rule plus `@condition` cascade rather than re-introducing `@value`. See "Roadmap entries" below.

## Implementation

R188 has two interlocking strands: (A) remove `@value` and switch UPDATE's SET/WHERE partition to PK-default; (B) extend `@condition` to four mutation placements and plumb the resulting WHERE through to the emitter. Strand B is the larger one. The two strands land in the same commit (or short commit sequence): the new "empty SET" diagnostic and the PK-coverage disjunction reference `@condition` outputs, and the override-true paths satisfy the PK-coverage gate.

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
  - Update the `LoadBearingClassifierCheck` annotation set: delete `mutation-input.update-set-fields-equal-value-marked`. The `mutation-input.where-columns-cover-pk` annotation rewords to the disjunction form (see strand B). Audit consumers — every `@DependsOnClassifierCheck(key = "mutation-input.update-set-fields-equal-value-marked", ...)` on `TypeFetcherGenerator`'s `reliesOn` clauses (around the bulk-UPDATE and bulk-UPSERT-prep blocks) needs the key reference removed. `LoadBearingGuaranteeAuditTest` will fail loudly on any orphan.
* `TypeFetcherGenerator.java` — no behavioural change to the SET/WHERE walk; the generator already walks `tia.setFields()` / `tia.lookupKeyFields()` and trusts the typed partition. The `reliesOn` JavaDoc comments referring to "`@value`-marked admissible carriers" need re-wording to "non-PK admissible carriers" (lines 1969, 1981-2000, 2061-2065, 2249-2284, 3741-3744). Mechanical.

### Strand B: `@condition` at four mutation placements

**Directive extension**

* `directives.graphqls` — extend `@condition`'s applicable locations from `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION` to add `INPUT_OBJECT`. Update the doc block to name the four placements and their reflection contracts (table + scope-appropriate values).
* `BuildContext.readConditionDirective(...)` — add an overload (or generalise) that reads `@condition` off a `GraphQLInputObjectType`. The existing implementation works element-agnostically for the directive payload; the SDL-location extension is the main lift.

**Resolver**

* `ConditionResolver.java` — add `resolveInputType(GraphQLInputObjectType inputType)` returning a sealed `InputTypeConditionResult` (None / Ok(ConditionFilter) / Rejected). Reflection binds the input type's field set against the method's parameter list via `ServiceCatalog.reflectTableMethod` with `TableSlotPolicy.REQUIRED`, mirroring `resolveField`. The existing `resolveField` and `resolveArg` paths stay unchanged — they're reused on the mutation side.
* `MutationInputResolver.java`:
  - In `resolveInput`, gather predicates from all four layers in order: input-type, `@mutation`-field, mutation-argument, input-field. The first three produce a `ConditionFilter` each (zero-or-one per layer); the fourth produces zero-or-more (one per `@condition`-bearing input field). Compose them into an ordered `List<ConditionFilter>` carried as a new `whereFilters` slot on `TableInputArg` (or on `MutationUpdateTableField` / sibling records; see model changes).
  - Lift the argument-level rejection at line 433: argument-level `@condition` on `@mutation` is now admitted on non-`@table` args. `@condition` on the `@table` arg itself stays rejected (the input-type-level placement is the right home for "this input's row identity").
  - Apply the override cascade: track an `enclosingOverride` flag analogous to `FieldBuilder.projectFilters:1318-1328` and suppress implicit PK / implicit `column = ?` predicates per the layer-local rule from the Design section.
  - Rewrite the PK-coverage gate as the disjunction: covered ∨ override-claims-where ∨ `multiRow: true`. Update the load-bearing-check description to name the disjunction.

**Model**

The WHERE-predicate stack lives on `TableInputArg`, not on each DML field record. Reasoning (per *Sub-taxonomies for resolution outcomes*): the disjunction PK-coverage check in `MutationInputResolver.resolveInput` is the invariant-bearing consumer, and the validator reads the layered filters there. The field records project from the arg.

* `ArgumentRef.java` — add a `whereFilters` slot on `TableInputArg`, typed as a sealed sub-taxonomy `WhereFilters` carrying layer-of-origin rather than as a flat `List<ConditionFilter>`:

  - `WhereFilters` is a record with four typed slots: `Optional<InputTypeLayer>`, `Optional<MutationFieldLayer>`, `List<ArgLayer>`, `List<InputFieldLayer>`. Each `*Layer` is a small record carrying the `ConditionFilter`, the `override` flag, and a layer-identifying tag.
  - Compact constructor expresses the structural-reject rules from the Design section as invariants: "two `override: true` layers on overlapping scopes is rejected" becomes a constructor check, not an ad-hoc resolver branch.
  - The emitter walks the layers in fixed order (input-type, mutation-field, arg, input-field) and AND-s them together; per-layer override flags are read directly, not inferred from list position.

* `MutationField.java` — DML field records (`MutationUpdateTableField`, `MutationDeleteTableField`, `MutationDmlRecordField`) gain *no* new slot; they pull `tableInputArg().whereFilters()` at emit time. `MutationUpsertTableField` carries the same path for R145's benefit. The model diff stays small; the typed projection lives on the arg.

**Emitter**

* `TypeFetcherGenerator.java` — UPDATE and DELETE arms (single-row + bulk + payload-returning variants) read `field.whereFilters()` and AND each filter into the emitted WHERE. The existing emission already builds WHERE from `lookupKeyFields` ColumnField bindings; the new step adds `.and(<method-call>)` per filter, threading the reflected `MethodRef.Param` extractions through the call-site `Map` lookup. For the bulk arm, the filters operate over the v-table reference (matching query-side bulk-condition emission). The override flag at the field record level suppresses the implicit PK predicates; emission switches from `.where(pk_col.eq(...))` to `.where(<filter>)` when the field is override-claimed.
* Reflection-shape note: the method takes the surrounding `@table`'s jOOQ table reference plus the scoped scalars, exactly like query-side `@condition`. This keeps single-row and bulk emit symmetric — no need to thread per-row jOOQ records into the method (which would break the bulk v-table shape). The "per-row record as method parameter" idea floated during spec drafting is rejected for this reason.

**Mutual exclusion and structural checks**

These rules express as compact-constructor invariants on `WhereFilters`, so the classifier produces a structurally well-formed layered filter set or fails loudly. A `Resolved.Rejected` carries the structural reason; the emitter never sees a malformed layer combination.

* Reject input-type-level `@condition` *and* input-field-level `@condition` with `override: true` on the same input type ("which override wins" has no non-arbitrary answer). Inner `@condition` *without* `override:` is preserved, per the cascade rule.
* Reject argument-level `@condition` on the `@table` input arg, with the diagnostic "argument-level `@condition` on the `@table` input arg is rejected; use input-type `@condition` instead — that's the right scope for this input's row-identity story". The diagnostic names the migration path so the SDL author isn't left guessing.
* Reject `@mutation`-field `@condition` co-occurring with input-type-level `@condition` and *both* carrying `override: true`: same "two scopes claiming the same suppression" problem.

**Load-bearing classifier checks**

The rewording of `mutation-input.where-columns-cover-pk` to a disjunction is itself a load-bearing change; the check renames to `mutation-input.where-identifies-row` and its description enumerates the three branches: PK-covered ∨ override-claims-where ∨ `multiRow: true`. Every `@DependsOnClassifierCheck(key = "mutation-input.where-columns-cover-pk", ...)` consumer needs re-verification, not just a key rename, since the guarantee shape changed.

Two new `@LoadBearingClassifierCheck` keys land in `MutationInputResolver`:

* `mutation-condition.method-shape-table-plus-scope-scalars` — pins the reflection contract: every `@condition` method on a mutation takes `(Table t, <scope-appropriate scalars>)`, never a per-row jOOQ record. The bulk-emit path depends on this for v-table compatibility; the validator must guarantee it.
* `mutation-condition.where-filters-well-formed` — pins the `WhereFilters` invariant set: at most one input-type layer, at most one mutation-field layer, no two-scope override conflicts. The emitter walks `whereFilters` without re-checking; the validator owns the structural promise.

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

Strand B coverage (the new placements):

* Input-type `@condition`, `override: false`, PK in input: admit with WHERE = `(PK auto-eq) AND (condition)`. Single-row and bulk variants.
* Input-type `@condition`, `override: true`, PK *not* in input, no `multiRow:`: admit with WHERE = `(condition)`; the PK-coverage gate is satisfied by the override.
* Input-type `@condition`, `override: true`, PK in input: admit with implicit PK predicates suppressed; SET still contains every non-PK column (the override changes WHERE, not the partition).
* `@mutation`-field `@condition`, no override: admit with both implicit PK predicates and the field-level condition AND-ed into WHERE.
* `@mutation`-field `@condition`, `override: true`: admit; implicit PK predicates suppressed; preserves any inner explicit `@condition` (regression-fence mirroring `filmsOuterOverrideTableInput`).
* Argument-level `@condition` on a `@mutation` field with a non-`@table` arg (e.g. `filter: String! @condition(...)`): admit; lifts the line-433 rejection. The non-table-arg shape doesn't participate in the partition; it only adds a WHERE predicate.
* Argument-level `@condition` on the `@table` arg of a `@mutation` field: reject ("`@condition` on the `@table` input arg: put it on the input type instead").
* Input-field-level `@condition`: admit with the per-field predicate AND-ed into WHERE; PK field with `@condition` keeps its implicit predicate (cascade); non-PK field with `@condition` does *not* move out of SET (the partition is pure; the predicate is additive).
* Two-layer override conflict: input-type `override: true` *and* input-field-level `override: true` on the same input type → reject (structural).

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

* **R145 (UPSERT, `mutation-cardinality-safety-upsert.md`):** R145's design fork is named-unique-key-driven (`conflictKey:` selects the conflict target, PK by default but any alternative unique index by name). That axis is *not* a subset of R188's "PK-default + `@condition` predicates": UPSERT's `ON CONFLICT (cols)` requires concrete columns from the input row, and `@condition` (an arbitrary predicate) is the wrong escape hatch for the conflict target. R188's contributions to R145: (a) drops `@value` from its partition description; SET clause becomes "input columns not in the conflict-target column set" (PK-default partition rule, generalised to "conflict-key-default"). (b) R145 inherits the four-placement `@condition` mechanism for layering predicates *on top of* the conflict-target match (e.g. tenant-scoped UPSERT). The `conflictKey:` selection and any named-unique-key shape stay R145's responsibility. Update R145's body to reflect both inheritances; no change to R145's status (still Backlog).
* **R146 (`mutation-cardinality-safety-unique-index.md`):** R188 doesn't relax the unique-index story; it relocates the question. Under R188, the WHERE side either covers PK (no `@condition`) or is driven by `@condition` (any layer, with `override: true` or `multiRow: true`); a non-PK unique-key WHERE driven *purely by input columns* (no `@condition`) no longer arises as a distinct shape. R146 stays Backlog with an addendum: if a future schema wants "uniquely identified by an alternate unique key from input columns" without writing a `@condition` method, R146 reopens.
* **R130 / R144:** both shipped; R188 is the next iteration of the same partition story plus the `@condition`-on-mutations lift. Add a `changelog.md` entry on R188's Done capturing both the directive removal *and* the four-placement `@condition` admission as milestones worth keeping in the historical record.

## Out of scope

* Lifting `@condition` to additional element types beyond `INPUT_OBJECT` (e.g. `OBJECT_TYPE` for output-side filtering on `@table` types). R188 only extends the directive to the input side.
* Validating the `@condition` method's body — its selectivity, its relationship to unique indexes, whether it really matches at-most-one row. The author's responsibility, exactly as on the query side. The `multiRow: true` opt-in and the `override: true` cascade are the structural acknowledgements; the runtime semantics are the author's.
* INSERT is not restructured. INSERT has no WHERE clause to attach `@condition` to (the `ON CONFLICT` story belongs to UPSERT under R145). The directive's location extension is permitted on `INPUT_OBJECT` at the SDL level; the mutation resolver rejects input-type `@condition` on an INSERT-side input with a deferred-feature diagnostic until R145 lands.
* DELETE *is* restructured to the extent that it inherits the four-placement `@condition` lift (DELETE has a WHERE clause and benefits from the same row-identity story). The PK-default partition doesn't apply to DELETE — DELETE has no SET — but the PK-coverage disjunction does, and the cascade works the same way. The Schema-migration and Tests sections cover DELETE alongside UPDATE; INSERT is untouched.
