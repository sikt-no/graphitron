---
id: R188
title: "Simplify UPDATE mutations: drop @value, infer SET/WHERE from PK metadata"
status: Spec
bucket: cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Simplify UPDATE mutations: drop @value, infer SET/WHERE from PK metadata

UPDATE mutations currently partition `@table` input fields by an explicit `@value` directive: marked fields become the SET clause, unmarked fields become the WHERE clause (required to cover the PK unless `multiRow: true`). For the overwhelming common case, this partition is mechanical and the schema already implies it via PK metadata: PK columns identify the row (WHERE), non-PK columns carry the new values (SET). The `@value` directive, the paired structural diagnostics ("no @value fields", "every field is @value-marked"), and the load-bearing classifier check `mutation-input.update-set-fields-equal-value-marked` (see `MutationInputResolver.java:319-323`) all exist to police a partition the catalog already knows. Non-PK row lookup ("update where `email = X`") is the genuinely interesting case and is better expressed through `@condition`, which is already the established mechanism for non-PK filtering and which is what users reach for anyway when the row-identity story diverges from the PK. Dropping `@value` removes a directive, two diagnostics, a load-bearing classifier check, and a structural-symmetry mismatch with INSERT (which has no SET/WHERE partition at all).

## Design

The partition on UPDATE becomes PK-driven, with `@condition` as the explicit escape hatch for non-PK row identity.

**Default partition (no `@condition` anywhere on the input):**

* Input fields whose target column is in the table's primary key contribute to `lookupKeyFields` (WHERE).
* Input fields whose target column is non-PK contribute to `setFields` (SET).
* The PK-coverage rule from R144 stays in place: every PK column must be covered by an input field, otherwise the UPDATE could match multiple rows. `multiRow: true` is no longer a valid opt-out on its own; without `@condition`, PK coverage is required.

**Escape hatch (`@condition` present on one or more input fields):**

* Every field carrying `@condition` is predicate-only: it contributes to WHERE via the condition method, and is excluded from both `lookupKeyFields` and `setFields`. This already matches the existing `@condition`-on-input-field semantics; nothing in the partition needs new machinery for it.
* Remaining (non-`@condition`) fields partition as before: PK → WHERE, non-PK → SET.

R144's `multiRow:` semantics are preserved as-is: `multiRow: true` is the opt-out for the PK-coverage check, regardless of whether `@condition` is also present. Three combinations land naturally:

* No `@condition`, PK covered, `multiRow: false` → row-identity UPDATE, the common case.
* `@condition` present, PK not in input, `multiRow: true` → predicate-driven UPDATE, possibly multi-row (the "update where `email = X`" shape, when `email` is not unique).
* No `@condition`, no PK in input, `multiRow: true` → naked broadcast UPDATE over the whole table. Rare but coherent (e.g. a maintenance-style "stamp `last_synced_at = now()` everywhere"). R188 keeps this shape expressible rather than foreclosing it; `multiRow: true` is the author's acknowledgement that the broadcast is intentional, which is exactly what R144 made it mean.

`@condition` and `@value` are currently mutually exclusive on the same input field; under R188 that exclusivity disappears with `@value` itself. `@condition` on the table-arg-level (currently rejected by `MutationInputResolver.resolveInput` at line 433) stays rejected for now; only per-field `@condition` participates in the partition.

**Schema-legibility trade.** Under `@value`, the SET/WHERE partition was visible from the SDL alone: every input field told you which side it was on. Under R188, a reader looking at `FilmUpdateInput.title` cannot tell from the SDL alone that `title` will land in SET while `filmId` lands in WHERE; they must know that `filmId` is the PK in the `film` table. This is a real reduction in schema-local legibility, accepted in exchange for removing a directive whose information was already in the catalog and whose two structural diagnostics existed to police a partition the catalog already knew. The user-doc rewrite is the load-bearing mitigation: `mutation.adoc` names the inference rule once, so readers learn "PK → WHERE, non-PK → SET" alongside the rest of the UPDATE story. The trade is intentional, not incidental.

**PK-change consequence.** Under `@value`, an author declared the partition explicitly, so a catalog migration that moved a column in or out of the PK left the SDL diagnostic-stable: existing schemas continued to behave the same way, even if the new partition would have been more sensible. Under R188, the same migration silently re-partitions every UPDATE input that includes the affected column at next build. The new behaviour is correct (the new PK *is* the new row identity), but it happens without any SDL change. This is an intended consequence of catalog-driven inference; the design assumes PK migrations are rare and accompanied by a deliberate review of mutation surfaces. No additional guard is added.

INSERT and DELETE are untouched: INSERT has no partition (every input field is a VALUES cell), DELETE has no SET clause (every input field is WHERE). Removing `@value` simplifies these arms too: the rejection clauses for "`@value` on DELETE / INSERT" disappear, since the directive no longer exists.

UPSERT is deferred to R145; this spec adjusts R145's plan body so its conflict-target / SET partition uses the same PK-default rule rather than re-introducing `@value`. See "Roadmap entries" below.

## Implementation

* `graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls:220-231` — remove the `@value` directive declaration and its doc block.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:97` — remove `DIR_VALUE`. Audit imports across the rewrite tree.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilder.java:340` — remove the `assertDirective(ctx, DIR_VALUE)` registration line.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/DmlKind.java` — delete `acceptsValueMarker()` and its callers.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java`
  - Drop the `valueMarkedNames` accumulation loop (lines 367-395) and the four rejection sites it gates: `@value` on DELETE / INSERT, `@value` + `@condition` co-occurrence, `@lookupKey` retirement keeps. The `@lookupKey` retirement check stays (the diagnostic mentions `@value` as a replacement; rewrite it to recommend `@condition`).
  - Drop the UPDATE-specific structural diagnostics at lines 482-493 ("no `@value` fields to set", "every input field is `@value`-marked"). The new failure mode (PK in input but no non-PK columns → empty SET) becomes the responsibility of a single structural check: if the partition produces an empty `setFields` on UPDATE, reject with "no non-PK columns to set; UPDATE has nothing to write". The complementary case (no PK columns in input) folds into the existing PK-coverage check.
  - The PK-coverage gate (lines 495-514) stays structurally unchanged: PK-coverage required by default, `multiRow: true` opts out. The only adjustment is the description of "covered": the union of admissible carriers now excludes `@condition`-marked fields (which are predicate-only).
  - Update the `LoadBearingClassifierCheck` annotation set: delete the `mutation-input.update-set-fields-equal-value-marked` annotation. The `mutation-input.where-columns-cover-pk` annotation stays, with its description updated for the new partition source.
  - Audit consumers of the deleted classifier key: every `@DependsOnClassifierCheck(key = "mutation-input.update-set-fields-equal-value-marked", ...)` on `TypeFetcherGenerator`'s `reliesOn` clauses (the search produces five-plus sites around the bulk-UPDATE and UPSERT-prep blocks) needs the key reference removed or replaced. The `LoadBearingGuaranteeAuditTest` will fail loudly on any orphan; the consumer-side sweep belongs in the same commit as the producer-side delete.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ArgumentRef.java` — rework `TableInputArg.of(...)` to drop the `valueMarkedNames` parameter and derive the partition from PK metadata read off `inputTable.tableName()` via `BuildContext`/`JooqCatalog`. The factory grows a `JooqCatalog` (or precomputed PK column set) argument; threading site is in `MutationInputResolver.resolveInput`. The `@condition` predicates are already on `fields`'s `ColumnField` carriers via the existing per-field walk; the factory filters them out of both partitions.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java` — the `valueMarkedNames` parameter on `buildLookupBindings` flows out symmetrically: bindings now derive WHERE-side from "PK columns in input", not from "input fields without `@value`". Refactor to read the PK set from the catalog rather than from a directive-marked name set.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java` — no behavioural change. The generator already walks `tia.setFields()` / `tia.lookupKeyFields()` and trusts the typed partition; the `reliesOn` JavaDoc comments referring to "`@value`-marked admissible carriers" need re-wording to "non-PK admissible carriers" (lines 1969, 1981-2000, 2061-2065, 2249-2284, 3741-3744, plus the surrounding bulk-UPDATE and bulk-UPSERT-prep blocks). Mechanical search-and-replace.

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

The four-tier discipline (unit / pipeline / compilation / execution; see `rewrite-design-principles.adoc:126-140`) governs which tier adds coverage:

* **Pipeline (primary):**
  - Update existing `GraphitronSchemaBuilderTest` UPDATE cases as listed above. Add one new pipeline case for the `@condition` predicate-driven shape: an UPDATE input that omits the PK column, carries one `@condition`-marked field, and uses `multiRow: true` → expect `MutationUpdateTableField` (admit) with WHERE driven entirely by the condition predicate.
  - Add one pipeline case for the broadcast shape: an UPDATE input with no PK columns and no `@condition`, with `multiRow: true` → expect `MutationUpdateTableField` (admit). This pins down that R188 preserves R144's `multiRow:` semantics rather than retightening them.
  - Add one rejection case for the new "empty SET" diagnostic: UPDATE input with only PK columns → UnclassifiedField with "no non-PK columns to set".
* **Compilation:** the sakila-example `updateFilm` / `updateFilms` / `updateFilmsPayload` mutations stay structurally identical (same PK partition, same SET columns); `mvn compile -pl :graphitron-sakila-example -Plocal-db` should pass with no further change beyond schema rewording.
* **Execution:** `DmlMutationsExecutionTest` / `DmlBulkMutationsExecutionTest` cover the single-row and bulk-UPDATE round trips. No new execution cases required for the default path. Add one execution case for the `@condition`-with-`multiRow:` escape hatch against the sakila `film.release_year` shape (mirror the existing R144 `deleteFilmsByReleaseYear` execution fixture but in UPDATE form), so the end-to-end "non-PK WHERE" story has a real-database proof.
* **Unit:** no new unit tests. The deleted `LoadBearingClassifierCheck` annotation removes one fixture from the classifier-check audit; verify nothing depends on its key.

## User documentation (first-client check)

The user-facing doc surface for R188 is small: one page deleted, one page revised.

* **Delete** `docs/manual/reference/directives/value.adoc`. The page is not cross-referenced from `directives/index.adoc` (`@value` is missing from the alphabetical and category lists; see `docs/manual/reference/directives/index.adoc:12-38`); no inbound xrefs in any user-facing doc resolve to it (the grep above found only false positives on the word "value"). Removal is a clean deletion.
* **Revise** `docs/manual/reference/directives/mutation.adoc`. The page's UPDATE section (lines 65, 120-123) still describes the obsolete pre-R144 `@lookupKey`-based partition; this work brings it in line with the post-R188 PK-default rule. Draft of the replacement prose:

[quote]
____
`UPDATE` partitions the `@table` input fields by primary-key membership: PK columns drive the WHERE clause (the row to update), non-PK columns drive the SET clause (the new values). The partition is read from the table catalog; no per-field directive is required.

For non-PK row identity (for example "update the film whose title is X"), add `@condition(method:)` to the field that carries the lookup value. Fields with `@condition` contribute their predicate to the WHERE clause and are not written to the SET clause.

When the WHERE side relies entirely on `@condition` (the input does not carry the PK), set `multiRow: true` on the `@mutation` directive to acknowledge that the predicate might match more than one row. `multiRow: true` is only valid in combination with `@condition`; without it the UPDATE has nothing to constrain the row count.
____

The replacement reads simply enough on its own; no further restructuring of `mutation.adoc` is required. The "UPDATE invariants" list now collapses to a single bullet ("at least one non-PK column must be present, otherwise the SET clause is empty") and the "where it applies" / "mutually exclusive with `@condition`" mentions vanish along with the `@value` page.

If `mutation.adoc` does not reduce to readable prose under this rewrite, the design is wrong and must change before implementation. The current draft above is short and self-contained, so the bar passes.

## Roadmap entries

* **R145 (UPSERT, `mutation-cardinality-safety-upsert.md`):** R145's design fork is named-unique-key-driven (`conflictKey:` selects the conflict target, PK by default but any alternative unique index by name). That axis is *not* a subset of R188's "PK-default + `@condition` for non-PK predicates": UPSERT's `ON CONFLICT (cols)` requires concrete columns from the input row, and `@condition` (an arbitrary predicate) is the wrong escape hatch for the conflict target. R188's contribution to R145 is narrower than the original spec body suggested: it removes the `@value`-based SET partition, so R145's SET clause becomes "the input columns not in the conflict-target column set" (PK-default partition rule, generalised to "conflict-key-default"). The `conflictKey:` mechanism and any named-unique-key shape stay R145's responsibility. Update R145's body to drop `@value` from its partition description and to defer the conflict-key selection design to itself; no change to R145's status (still Backlog).
* **R146 (`mutation-cardinality-safety-unique-index.md`):** R188 doesn't relax the unique-index story; it relocates the question. Under R188, the WHERE side either covers PK (no `@condition`) or is driven by `@condition` (with `multiRow: true`); a non-PK unique-key WHERE driven by input columns no longer arises as a distinct shape. R146 stays Backlog with a one-paragraph addendum noting the relocation: if a future schema wants "uniquely identified by an alternate unique key from input columns" without `@condition`, R146 reopens.
* **R130 / R144:** both shipped; R188 is the next iteration of the same partition story. Add a `changelog.md` entry on R188's Done capturing the directive removal as a milestone worth keeping in the historical record.

## Out of scope

* Argument-level `@condition` on a `@mutation` field stays rejected (`MutationInputResolver.resolveInput` line 433). Lifting it is a separate design question; the per-field `@condition` shape covers the cases R188 needs to express.
* The `@condition` predicate method's body is the author's responsibility, exactly as it is on query-side `@condition` today. R188 does not try to validate the predicate's selectivity or its relationship to unique indexes; the `multiRow: true` opt-in is the structural acknowledgement that broadcasts may happen.
* INSERT and DELETE are not restructured. INSERT has no partition; DELETE has no SET. Removing `@value` clarifies these arms by removing rejection clauses that referred to a directive that no longer exists.
