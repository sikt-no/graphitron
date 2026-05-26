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

UPDATE mutations currently partition `@table` input fields by an explicit `@value` directive: marked fields become the SET clause, unmarked fields become the WHERE clause (required to cover the PK unless `multiRow: true`). For the overwhelming common case, this partition is mechanical and the schema already implies it via PK metadata: PK columns identify the row (WHERE), non-PK columns carry the new values (SET). The `@value` directive, the paired structural diagnostics ("no @value fields", "every field is @value-marked"), and the partition's `set-equals-value-marked` audit-key invariant carried in `ArgumentRef.TableInputArg.of`'s javadoc (`ArgumentRef.java:271`) all exist to police a partition the catalog already knows.

For the *interesting* case — non-PK row identity ("update where `email = X`") — `@condition` is the right mechanism, mirroring the query side. The current state on mutations is that `@condition` at the SDL parses, is structurally checked, but is *not wired through to mutation WHERE at all*: argument-level `@condition` on the `@table` arg is rejected outright (`MutationInputResolver.java:439`), input-field-level `@condition` admits only with `override: true` and is silently dropped from emit (R215, `MutationInputResolver.java:384-400, 482-497`), and `MutationUpdateTableField` has no slot to carry a resolved `ConditionFilter` (`MutationField.java:72-83`). R188 plumbs `@condition` through to mutation WHERE at four placements — input type, `@mutation` field, mutation argument, input field — composed via the existing query-side cascade rules, and pairs that with dropping `@value` and the PK-default partition. The two changes interlock: the partition rule, the override semantics, the PK-coverage check, and the SET-stability rule all reference each other and have to be designed together.

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

* `RowIdentity.KeyLookup` — PK columns are present in the input as admissible carriers (R144's original case). Carries (a) the implicit PK-column predicates and (b) an optional `additionalExplicit: ConditionFilter` composing any non-override `@condition` layers (mutation-field, arg-on-non-table, input-field, input-type-without-override) the classifier has AND-ed together. The emitter emits `.where(<pk-predicates> AND <additionalExplicit>)` — one expression, no fork.
* `RowIdentity.Predicate` — an `override: true` `@condition` at some layer has claimed responsibility for row-identity. Carries the *composed* `ConditionFilter` payload (the AND of the override layer plus any inner non-override `@condition`s, preserved per the `filmsOuterOverrideTableInput` regression-fence). Implicit PK predicates are suppressed by variant identity, not by a separate flag.
* `RowIdentity.BulkKeyLookup` — `multiRow: true` is set on `@mutation` (R144's broadcast opt-in preserved). No payload; the bulk v-table path takes over.

The classifier produces *exactly one* `RowIdentity` variant per UPDATE/DELETE field, with the composed payload pre-resolved at classify time. The validator's invariant is "this field has a `RowIdentity`"; the emitter switches exhaustively on the variant identity and reads the variant's payload directly. The boolean-disjunction shape R144 carried ("any of three OR-branches must hold") is the wrong target for an invariant the emitter has to fork on — modelling the three facts as a sealed type is what lets the validator and emitter branch on the same identity rather than re-deriving the disjunction at emit time.

Per Generation-thinking, the emitter never walks layered `@condition` inputs. The four `@condition` source sites are read once by the classifier, the composition runs at classify time, and the variant's payload is the composed result. The emitter consumes pre-resolved variant payloads.

The naming is forward-compatible with R222 (the dimensional-model umbrella, which absorbed the former R164). R222 introduces a `PredicateCarrier` sealed family with `Condition` and `LookupRows` arms; R188's `RowIdentity` lift will collapse into the per-record shape under the future `UpdateRows` slice when that lands. R188 does *not* preempt that slice: the slot lives on the field record under the current sealed taxonomy, the type is named `RowIdentity` (not `LookupRows`), and the structural-rejection invariants stay observable in the classifier so R222's slice has a stable contract to refactor against. See "Roadmap entries" below for the coordination commitment.

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
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilder.java:495` — remove the `assertDirective(ctx, DIR_VALUE)` registration line.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/DmlKind.java` — delete `acceptsValueMarker()` and its callers.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ArgumentRef.java` — rework `TableInputArg.of(...)` to drop the `valueMarkedNames` parameter and derive the partition from PK metadata read off `inputTable.tableName()` via `JooqCatalog`. The factory grows a `JooqCatalog` (or precomputed PK column set) argument; threading site is in `MutationInputResolver.resolveInput`. Update the `set-equals-value-marked` audit-key javadoc at `ArgumentRef.java:271` to describe the new PK-driven rule.
* `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java` — the `valueMarkedNames` parameter on `buildLookupBindings` flows out symmetrically: bindings derive WHERE-side from "PK columns in input", not from "input fields without `@value`". Refactor to read the PK set from the catalog rather than from a directive-marked name set.
* `MutationInputResolver.java`:
  - Drop the `valueMarkedNames` accumulation loop (current lines 356-401) and the rejection sites it gates: `@value` on DELETE / INSERT, `@value` + `@condition` co-occurrence. The `@lookupKey` retirement check stays; rewrite its diagnostic to recommend `@condition` instead of `@value`.
  - Drop the UPDATE-specific structural diagnostics at current lines 509-520 ("no `@value` fields to set", "every input field is `@value`-marked"). The new failure mode (PK in input, no non-PK columns) becomes a single structural check: if `setFields` is empty on UPDATE and no `override: true` `@condition` claims responsibility for the SET shape (it can't; `@condition` doesn't write), reject with "no non-PK columns to set; UPDATE has nothing to write".
  - Audit `TypeFetcherGenerator`'s bulk-UPDATE and bulk-UPSERT-prep blocks for any javadoc / comment references to the retired `mutation-input.update-set-fields-equal-value-marked` invariant and to the pre-rename `mutation-input.where-columns-cover-pk` shape; the structural contract migrates to the typed `RowIdentity` slot on the field (see strand B). The compiler exhaustiveness check on the `RowIdentity` sealed switch is the safety net.
* `TypeFetcherGenerator.java` — no behavioural change to the SET/WHERE walk; the generator already walks `tia.setFields()` / `tia.lookupKeyFields()` and trusts the typed partition. The `reliesOn` JavaDoc comments referring to "`@value`-marked admissible carriers" need re-wording to "non-PK admissible carriers". Mechanical; locate via `grep -n '@value' TypeFetcherGenerator.java`.

### Strand B: `@condition` at four mutation placements

**Directive extension**

* `directives.graphqls` — extend `@condition`'s applicable locations from `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION` to add `INPUT_OBJECT`. Update the doc block to name the four placements and their reflection contracts (table + scope-appropriate values).
* `BuildContext.readConditionDirective(...)` — add an overload (or generalise) that reads `@condition` off a `GraphQLInputObjectType`. The existing implementation works element-agnostically for the directive payload; the SDL-location extension is the main lift.

**Resolver**

* `ConditionResolver.java` — add `resolveInputType(GraphQLInputObjectType inputType)` returning the existing sealed `ConditionResolver.Resolved` shape (None / Ok(ConditionFilter) / Rejected). Reflection binds against the method's parameter list via `ServiceCatalog.reflectTableMethod` with `TableSlotPolicy.REQUIRED`, mirroring `resolveField`. The method may take any subset of the input type's field scalars; `argMapping:` is supported (same semantics as query-side leaf `@condition` — rebind GraphQL field names to differently-named Java parameters). The existing `resolveField` and `resolveArg` paths stay unchanged — they're reused on the mutation side.
* `MutationInputResolver.java`:
  - Read the four `@condition` sources from their natural homes (see Model below): the input type's directive via `ConditionResolver.resolveInputType`; the `@mutation` field's directive via `ConditionResolver.resolveField`; each non-`@table` argument's directive via `ConditionResolver.resolveArg`; each input field's already-resolved `Optional<ArgConditionRef>` via `InputField.condition()`.
  - Lift the argument-level rejection currently at line 439: argument-level `@condition` on `@mutation` is now admitted on non-`@table` args. `@condition` on the `@table` arg itself stays rejected with a migration-pointing diagnostic (the input-type-level placement is the right home for "this input's row identity").
  - Compose the resolved layers into the field's `RowIdentity` variant in priority order: any-layer-`override:true` → `RowIdentity.Predicate(<override layer's filter AND-ed with any inner non-override filters>)`; else `@mutation(multiRow: true)` → `RowIdentity.BulkKeyLookup`; else PK columns present in input → `RowIdentity.KeyLookup(<implicit PK predicates>, <optional additionalExplicit = AND of any non-override layers>)`; else reject via `mutation-input.where-identifies-row`. The priority ordering is the disjunction made deterministic: an author who writes `override:true` *and* `multiRow:true` gets `Predicate` (the more specific signal), not silent precedence ambiguity.
  - Apply the override-cascade preservation rule from the Design section in the composition step: when the selected variant is `Predicate`, inner non-override `@condition` annotations still contribute to the composed payload (the `filmsOuterOverrideTableInput` regression-fence applies on the mutation side).

**Model**

Each `@condition` placement lives at its natural home; there is no aggregating `WhereFilters` carrier. The four sources and their slots:

| Layer | Slot | Notes |
|---|---|---|
| Input type | New `Optional<ConditionFilter> inputTypeCondition` on `ArgumentRef.InputTypeArg.TableInputArg` | Populated by `MutationInputResolver` (or `TypeBuilder`, mirroring where input-type-level directive reads happen today) via `ConditionResolver.resolveInputType`. |
| `@mutation` field | New `Optional<ConditionFilter> fieldCondition` on `MutationUpdateTableField`, `MutationDeleteTableField`, and prospectively `MutationUpsertTableField` (for R145's tenant-scoping use; UPSERT's conflict target is a different axis). | Populated by `MutationInputResolver.resolveInput` via `ConditionResolver.resolveField`. |
| Mutation argument (non-`@table` arg) | New `List<ArgConditionRef> argConditions` on the same field records | Populated by `MutationInputResolver` via `ConditionResolver.resolveArg` on each non-`@table` argument. |
| Input field | Existing `Optional<ArgConditionRef> condition` on `InputField` permits | Already populated by `BuildContext.classifyInputField`; no model change. |

The field record also carries the resolved `RowIdentity identity` slot. The classifier computes it once: reads the four sources above, applies the structural-rejection rules (see "Structural checks" below), composes the surviving `ConditionFilter`s into the variant's payload, and stores the result.

Why the slot homes are split this way: input-type `@condition` describes the input *itself* (the row-spec's identity story shared across every mutation using this input), so its slot belongs on the input arg's carrier. Field-level and arg-level `@condition`s describe the *operation* (per-mutation overrides, ad-hoc filter args), so their slots belong on the field record. Each slot lives where the directive's scope says it lives; no slot aggregates layers across scope boundaries.

`MutationField.java` — `MutationUpdateTableField` and `MutationDeleteTableField` gain `fieldCondition`, `argConditions`, and `identity` slots; `MutationDmlRecordField` follows the same shape on its DELETE-equivalent path; `MutationUpsertTableField` gets the same three slots prospectively. The `identity` slot is set by `MutationInputResolver.resolveInput` per the priority-ordered selection above; no field record computes the variant at emit time.

**Emitter**

`TypeFetcherGenerator.java` — UPDATE and DELETE arms (single-row + bulk + payload-returning variants) switch exhaustively on `field.identity()`:

  - `RowIdentity.KeyLookup(implicitPk, additionalExplicit)` → emit `.where(<implicitPk> AND <additionalExplicit>)` (or just `<implicitPk>` when `additionalExplicit` is absent). The implicit-PK shape is unchanged from R144 (`.where(pk_col.eq(...))` AND-ed across PK columns). The `additionalExplicit` payload, when present, is a single pre-composed `ConditionFilter` the emitter binds against the call-site `Map` the same way today's argument-level `@condition` already does.
  - `RowIdentity.Predicate(composed)` → emit `.where(<composed>)` directly. The variant identity is the suppression signal for implicit PK predicates; no separate flag read. Threading remains the same as today: the composed `ConditionFilter`'s reflected `MethodRef.Param` extractions resolve against the call-site `Map`. For the bulk arm, the filter operates over the v-table reference (matching query-side bulk-condition emission).
  - `RowIdentity.BulkKeyLookup` → no row-identity predicates; emission delegates to the existing bulk v-table path which already does broadcast updates with no WHERE.

The emitter never walks `@condition` layer sources at emit time. The composition runs once at classify time, and the variant's payload is the pre-resolved result — per Generation-thinking, the emitter consumes the resolved decision rather than re-deriving it from layered inputs.

Reflection-shape note: each `@condition` method takes the surrounding `@table`'s jOOQ table reference plus the scope-appropriate scalars (subset of the input's field set or scope-appropriate args; `argMapping:` is supported on the directive). This matches the v-table-safe parameter shape pinned by `mutation-condition.method-shape-table-plus-scope-scalars` (see below) — the bulk arm requires it because per-row jOOQ records have no v-table analogue. The "per-row record as method parameter" idea floated during spec drafting is rejected for this reason. Query-side leaf `@condition` may still receive a carrier record via different paths; the mutation-side constraint is narrower because it must work under bulk emit.

**Structural checks**

Applied at classify time in `MutationInputResolver` (composition step) and `ConditionResolver` (per-layer reflection). The classifier reads the four sources, applies these rules, and either produces the resolved `RowIdentity` or rejects as `UnclassifiedField`. The emitter never sees a malformed combination.

* **At most one `override: true` across the four placements.** Two layers both carrying `override: true` have overlapping suppression scope: each layer's override claims responsibility for suppressing some implicit predicate the other also claims, and the resulting WHERE shape is ambiguous. Reject with a diagnostic naming the conflicting placements. The four placements are not a strict containment chain — they're four scopes that can overlap on the same column's implicit predicate, and any two overrides asserting overlapping suppression is the universal failure mode.
* **`@condition` on the `@table` input arg is rejected**, with the diagnostic "argument-level `@condition` on the `@table` input arg is rejected; use input-type `@condition` instead — that's the right scope for this input's row-identity story". The diagnostic names the migration path so the SDL author isn't left guessing.
* **`override: true` on a non-PK input field is rejected as no-op-override.** A non-PK input field has no implicit predicate to suppress (it's a SET contribution; the partition is pure per the Design section). Diagnostic: "override: true on input field '<name>' is a no-op (non-PK fields have no implicit predicate; remove the override flag or move the @condition to a layer with broader scope)".
* **Inner explicit `@condition` annotations are always preserved**, regardless of which outer layer overrides. The `filmsOuterOverrideTableInput` regression-fence applies on the mutation side; the composition step AND-s inner non-override filters into the selected variant's payload.

**Structural invariants the validator produces**

The reshape of "PK columns are in the input" to "the field has a `RowIdentity` variant" pushes the contract from a boolean into a sealed type: the field's `identity()` slot carries one of `RowIdentity.{KeyLookup, Predicate, BulkKeyLookup}`, or the field is `UnclassifiedField`. The validator produces a variant, not a boolean; the emitter consumes the variant identity directly and switches exhaustively (compiler exhaustiveness on `RowIdentity` is the safety net) rather than asserting a boolean and re-deriving the branch.

Three further structural invariants land in `MutationInputResolver`:

* `mutation-condition.method-shape-table-plus-scope-scalars` — pins the reflection contract: every `@condition` method on a mutation takes `(Table t, <scope-appropriate scalars>)`, never a per-row jOOQ record. The bulk-emit path depends on this for v-table compatibility; the validator must guarantee it. `ConditionResolver` enforces at reflection time.
* `mutation-input.where-identifies-row` — the field has a `RowIdentity` variant (one of the three permits) or the field is `UnclassifiedField`. Composition output, not a separate check.
* `mutation-update.row-identity-matches-payload` — if `RowIdentity.KeyLookup`, the field's `lookupKeyFields` is non-empty; if `RowIdentity.Predicate`, at least one of the field's four `@condition` sources carried `override: true`; if `RowIdentity.BulkKeyLookup`, the field's `@mutation` carries `multiRow: true`. Compact-constructor check on the field record. This guarantees the variant identity matches the payload the consumer reads.

## Schema migration

* `graphitron-rewrite/graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:1232-1245` — strip `@value` from `FilmUpdateInput.title` and `FilmUpdateInput.description`; rewrite the comment block to describe PK-driven inference. No structural change to the input itself: `filmId` is still PK and still becomes WHERE; `title` and `description` are still non-PK and still become SET.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java` — strip `@value` from every embedded SDL snippet (locate via `grep -n '@value'`; current count is 30 site lines). The cases that exercised the deleted diagnostics convert as follows:
  - "UPDATE without any `@value` fields → UnclassifiedField" (current lines 6841-6852) becomes "UPDATE where the input contains only PK columns → UnclassifiedField with 'no non-PK columns to set'".
  - "UPDATE where every input field is `@value`-marked → UnclassifiedField" (current lines 6854-...) becomes "UPDATE where the input contains no PK columns, no `@condition`, and `multiRow:` is absent → UnclassifiedField via PK-coverage failure". (With `multiRow: true` the same shape admits as a broadcast UPDATE.)
  - "`@value` on DELETE → UnclassifiedField" and "`@value` + `@condition` mutually exclusive" (current lines 7370-7398) delete outright; under R188 these aren't possible.
  - The TableInputArg projection test (current line 6945) keeps its assertion shape: `setFields` in declaration order, but the partition source is PK-vs-non-PK instead of `@value`.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/SingleRecordPayloadPipelineTest.java:377-382, 610-612` — strip `@value` from the UPDATE and UPSERT cases. UPSERT cases continue to use UPSERT-rejection diagnostic until R145 lands.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java:446, 447, 493, 494, 565, 566` — strip `@value`.
* `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/MutationDmlNodeIdClassificationTest.java:100-103, 148, 176, 459, 473` — strip `@value` and adjust the assertion at line 103 (the "no `@value` fields to set" diagnostic) to the new "no non-PK columns to set" wording. Cases at 148 / 176 / 473 lose the `@value` keyword and keep working unchanged (`name` is non-PK).

## Tests

The four-tier discipline (unit / pipeline / compilation / execution; see `rewrite-design-principles.adoc:126-140`) governs which tier adds coverage.

### Pipeline (primary)

Strand A updates:

* `GraphitronSchemaBuilderTest` cases listed under "Schema migration" above adjust to the new diagnostics and the PK-default partition.
* Add a "broadcast shape" admit case: UPDATE input with no PK columns and no `@condition`, with `multiRow: true` → `MutationUpdateTableField` (admit). Pins down that R188 preserves R144's `multiRow:` semantics rather than retightening them.
* Add an "empty SET" rejection case: UPDATE input with only PK columns → UnclassifiedField with "no non-PK columns to set".

Strand B coverage (the new placements). Each admit case asserts the resolved `RowIdentity` variant on the field record, not just admit/reject; the variant assertion is the load-bearing piece that `mutation-update.row-identity-matches-payload` pins:

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

No new unit tests.

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

* **R222 (dimensional model pivot, `dimensional-model-pivot.md`, Spec):** R222 is the umbrella that absorbed the former R164. R222 introduces a `PredicateCarrier` sealed family with `Condition` (read-side) and `LookupRows` (mutation-side) arms, and an `UpdateRows` carrier on a narrow interface (slot home TBD per R222's slice planning). R188 lands ahead of R222 and is shaped for forward-compatibility: R188's `RowIdentity` sub-taxonomy on the UPDATE / DELETE field record uses the same three-branch semantics R222's `UpdateRows` / `LookupRows` slice is expected to formalise. R222's lift on R188's contributions will likely be a rename-plus-promotion (rename `RowIdentity` to the R222-chosen carrier name, hoist it from the per-record sealed taxonomy into the dimensional slot), not structural rework. R188 does *not* preempt R222: the slot lives on the field record under the current sealed taxonomy (not on the dimensional slot, which doesn't exist yet), the type is named `RowIdentity` (not `LookupRows` or whatever R222 picks), and the structural-rejection invariants (`mutation-input.where-identifies-row`, `mutation-update.row-identity-matches-payload`, `mutation-condition.method-shape-table-plus-scope-scalars`) stay observable in the classifier so R222's slice has a stable contract to refactor against. R222's slice note captures the corresponding commitment: when the `UpdateRows` / `PredicateCarrier` slot lands, preserve R188's three variant semantics and the composed-payload shape; the per-layer reads can be retained or rewired through R222's walker carriers per the slice's choice.
* **R145 (UPSERT, `mutation-cardinality-safety-upsert.md`):** R145's design fork is named-unique-key-driven (`conflictKey:` selects the conflict target, PK by default but any alternative unique index by name). That axis is *not* a subset of R188's "PK-default + `@condition` predicates": UPSERT's `ON CONFLICT (cols)` requires concrete columns from the input row, and `@condition` (an arbitrary predicate) is the wrong escape hatch for the conflict target. R188's contributions to R145: (a) drops `@value` from its partition description; SET clause becomes "input columns not in the conflict-target column set" (PK-default partition rule, generalised to "conflict-key-default"). (b) R145 inherits the four-placement `@condition` mechanism for layering predicates *on top of* the conflict-target match (e.g. tenant-scoped UPSERT). The `conflictKey:` selection and any named-unique-key shape stay R145's responsibility. The R145 body edit lands in the same commit that drops `@value` (Strand A) — R145 currently reads "The `@value` partition extends naturally from R144", which becomes stale the moment Strand A ships and must not be left dangling. No change to R145's status (still Backlog).
* **R146 (`mutation-cardinality-safety-unique-index.md`):** R188 doesn't relax the unique-index story; it relocates the question. Under R188, the WHERE side either covers PK (`RowIdentity.KeyLookup`) or is driven by `@condition` (`RowIdentity.Predicate`, any layer override) or is broadcast (`RowIdentity.BulkKeyLookup`); a non-PK unique-key WHERE driven *purely by input columns* (no `@condition`) no longer arises as a distinct shape. R146 stays Backlog with an addendum: if a future schema wants "uniquely identified by an alternate unique key from input columns" without writing a `@condition` method, R146 reopens.
* **R130 / R144:** both shipped; R188 is the next iteration of the same partition story plus the `@condition`-on-mutations lift. Add a `changelog.md` entry on R188's Done capturing the directive removal, the four-placement `@condition` admission, *and* the `RowIdentity` sealed sub-taxonomy as milestones worth keeping in the historical record. Cross-reference R222 from the changelog entry so the R222 `UpdateRows` / `PredicateCarrier` slice implementer finds the R188 coordination thread.

## Out of scope

* Lifting `@condition` to additional element types beyond `INPUT_OBJECT` (e.g. `OBJECT_TYPE` for output-side filtering on `@table` types). R188 only extends the directive to the input side.
* Validating the `@condition` method's body — its selectivity, its relationship to unique indexes, whether it really matches at-most-one row. The author's responsibility, exactly as on the query side. The `multiRow: true` opt-in and the `override: true` cascade are the structural acknowledgements; the runtime semantics are the author's.
* INSERT is not restructured. INSERT has no WHERE clause to attach `@condition` to. The directive's location extension is permitted on `INPUT_OBJECT` at the SDL level; the mutation resolver rejects input-type `@condition` on an INSERT-only input with the diagnostic "INSERT has no WHERE clause; @condition is not applicable on an INSERT input". The rejection is permanent — no future roadmap item is planned that would admit it. (R145's UPSERT does have an `ON CONFLICT` predicate target, but that's the conflict-key axis, not `@condition`; see R145 above for the UPSERT-specific composition.)
* DELETE *is* restructured to the extent that it inherits the four-placement `@condition` lift (DELETE has a WHERE clause and benefits from the same row-identity story). The PK-default partition doesn't apply to DELETE — DELETE has no SET — but the `RowIdentity` sub-taxonomy does, and the cascade works the same way. The Schema-migration and Tests sections cover DELETE alongside UPDATE; INSERT is untouched.
