---
id: R363
title: "Lower @field filter inputs and @condition onto multitable-interface queries"
status: In Review
bucket: bug
priority: 2
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-25
---

# Lower @field filter inputs and @condition onto multitable-interface queries

## Problem

A `@field`-mapped filter input (and `@condition`) on a Query field that returns a multitable
interface or union is silently dropped during code generation. Codegen succeeds,
`graphitron:validate` passes, the query runs, and it returns every row unfiltered, with no error or
warning. In the reporting context (an access-control subgraph whose `Applikasjon` interface spans
`feide_applikasjon` / `maskinporten_applikasjon` / `maskinbruker_applikasjon`) that silently leaks
the full table where the consumer asked for a filtered slice, so the severity is data-correctness,
not ergonomics. Reported as a 9.x to 10.x regression: 9.3 threaded the filter into every UNION
branch. The rewrite never built the path, so the regression framing is right under the
feature-equivalence goal; confirm the exact 9.3 generated shape before citing it externally.

## Mechanism (confirmed by source trace)

The filter has nowhere to live, at any layer; this is a missing feature across model, builder,
validator, and emitter, not one dropped call.

- **Model has no slot.** `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField` carry only
  `parentTypeName, name, location, returnType, participants`; no `filters`, `orderBy`, `pagination`,
  or `condition`. Contrast `QueryTableField` and the single-table `QueryTableInterfaceField`, which
  carry `List<WhereFilter>`. `operation()` hardcodes `List.of()` filters and `OrderBySpec.None()` for
  the polymorphic arms.
- **Builder never parses the arguments.** `FieldBuilder` (interface/union arms, around lines
  3385-3394) constructs the record from `participants()` alone and never calls
  `resolveTableFieldComponents(...)`, the only method that turns field arguments, `@field`-mapped
  inputs, and `@condition` into `WhereFilter`s (and that can return `Rejected`). Every call site of
  that method is on a table-bound path.
- **Emitter reads only pagination.** `MultiTablePolymorphicEmitter` calls `env.getArgument` only for
  `first`/`last`/`after`/`before`; `buildStage1Block` / `buildStage1ConnectionBlock` emit a bare
  `UNION ALL` with no per-branch WHERE for root queries. The one WHERE-producing helper
  (`branchParentFkWhere`) exists only for child fetchers' parent-FK predicate.
- **`@condition` is unvalidated on this path.** Because the directive is never lowered into the model,
  a `@condition` naming a non-existent method passes `graphitron:validate`:
  `GraphitronSchemaValidator.validateQueryInterfaceField` / `validateQueryUnionField` only check
  cardinality and participants, never the `@condition` predicate.

Do not conflate these multitable variants with `QueryTableInterfaceField`, the single-table
discriminator interface, which does carry and apply filters (it emits a discriminator IN-filter) and
is unaffected.

## Plan

The fix mirrors the single-table discriminator interface (`QueryTableInterfaceField`), which already
carries and applies a filter surface (`QueryField.java:189-204`); the two multitable polymorphic
variants are the only catalog-bound query fields without it.

**Scope (Spec review 2026-06-25): `@field` filters only. `orderBy` is cut to a follow-up (R382).**
The original draft put `orderBy` in day-one scope alongside filters, but the multitable emitter
hardcodes the synthetic `__sort__` key as the participant PK, and on the connection path `__sort__`
*is* the Relay cursor seek key (round-tripped through `ConnectionHelper.encodeCursor` /
`decodeCursor` with `__typename ASC` as the tiebreaker). Threading a user `orderBy` means projecting
a chosen column into every UNION branch, replacing `__sort__` as the sort and cursor key, and
round-tripping it through the cursor codec, materially more work than the filter `.where(...)`
threading this item does, and the reported defect is purely the dropped `@field` filter. Carrying
an `orderBy` model slot the emitter ignores would re-create the producer-carries / consumer-drops
gap this item exists to close. So day one keeps `operation()` passing `new OrderBySpec.None()` for
these arms unchanged; R382 owns the `__sort__`/cursor reconciliation.

**Resolved design decision (Spec review): the filter surface is per-participant, not a single shared
list.** A `GeneratedConditionFilter` names a *table-specific* generated condition method and column
constants (`WhereFilter.java`): the same logical `@field` filter on `ORGANISASJONSKODE` resolves to
`FeideApplikasjonConditions.…(FEIDE_APPLIKASJON.ORGANISASJONSKODE, …)` for one participant and
`MaskinportenApplikasjonConditions.…(MASKINPORTEN_APPLIKASJON.…)` for another — *different*
`WhereFilter`s. So the builder lowers the filter arguments once **per participant table**, against
each participant's own `TableRef`, and the model carries the resolved filters keyed by participant.
This single decision settles the three questions the original draft left open:

- **Which layer rejects a column absent from a participant — builder or validator?** The classifier
  (builder). Lowering the filter against a participant whose table lacks the `@field`-named column
  makes that participant's `resolveTableFieldComponents` return `TableFieldComponents.Rejected` (the
  existing `unknownColumn` typed rejection), which fails the build naming the participant and column.
  No separate validator pass is invented; per "Validator mirrors classifier invariants" the validator
  already surfaces classifier rejections. The contract — "every participant carries the filtered
  column" (the 9.x behaviour) — is thus enforced at classify time, not as a runtime scope-narrowing.
- **Column-type compatibility across participants.** Falls out of the same per-participant lowering:
  each participant's arg→column binding is validated against that participant's column `DataType`
  (`DSL.val(rawValue, col.getDataType())` binds per branch). A same-named column with an incompatible
  type on one participant surfaces as that participant's rejection, not a latent per-branch bind hole.
- **`@condition` scope (day one).** Out of scope for this slice, and **rejected — not silently
  dropped**. A developer `@condition` method takes a single concrete jOOQ `Table` first parameter; a
  multitable field has N participant tables, so per-participant `@condition` lowering needs an emitted
  per-participant adapter, a real design that a future item can take up. Day one lowers `@field`-mapped
  column filters (the reported data-correctness bug) and rejects a `@condition` on the multitable path
  with a typed rejection, closing the silent-pass hole the Mechanism section flags. **The rejection
  uses a non-deferred kind (`Rejection.structural(...)`), not `Rejection.deferred(slug)`.** Nothing in
  the build pins a deferred `planSlug` to a real roadmap file (shipped slugs already dangle), so a
  slug here would be an unenforced dangling pointer, the exact hazard the predecessor cluster had to
  fix. A `@condition` on this path is "recognised but unsupported on this site", which `structural`
  expresses without a slug, so no follow-up item need exist for the rejection to be honest.

1. **Model: add the per-participant filter surface.** Give `QueryField.QueryInterfaceField` and
   `QueryField.QueryUnionField` (`QueryField.java:212-245`) a *per-participant* filter carrier. The
   carrier must be per-participant: the same logical `@field` filter resolves to a *different*
   table-specific `WhereFilter` per participant, so a single shared `List<WhereFilter>` cannot serve
   it. The carrier is **field-local** — a small new record pairing each table-bound participant with
   its filters (e.g. `record ParticipantFilters(ParticipantRef.TableBound participant,
   List<WhereFilter> filters)`), held in a `List` on the field. **Do not add a `filters` component to
   `ParticipantRef.TableBound` itself.** That type is type-scoped and shared: `participants()` comes
   from the cached `InterfaceType` / `UnionType` verdict, so the same interface backing two query
   fields shares those `ParticipantRef` instances, and filters are a per-*field* concern. Loading them
   onto the shared participant would force every other `TableBound` construction site (single-table
   `TableInterfaceType`, `QueryServicePolymorphicField`, child fields) to thread an empty list, and
   diverges from the codebase's own precedent — `QueryTableInterfaceField` carries a *separate*
   field-level `filters`, not filters-on-participants. A field-local pairing also keys on the
   participant object, not a stringly typename, so it cannot drift from the participant set. No
   `orderBy` slot this slice (see Scope above; R382). Pagination already flows through the connection
   wrapper, so day-one scope is `filters` only.

   **Do *not* make these fields `implements SqlGeneratingField`.** The mirror with
   `QueryTableInterfaceField` ends here: that field is single-table (one `TableBoundReturnType` plus a
   discriminator column), but the multitable variants carry `ReturnTypeRef.PolymorphicReturnType`.
   `SqlGeneratingField.returnType()` returns `TableBoundReturnType`, a *sibling* variant of
   `PolymorphicReturnType`, so a record whose `returnType()` accessor returns the polymorphic type
   cannot satisfy the interface — it does not compile. Beyond the compile wall, the capability's flat
   `filters()` contradicts the per-participant decision, and implementing it opts these fields into
   every `instanceof SqlGeneratingField` consumer — `TypeConditionsGenerator`
   (`TypeConditionsGenerator.java:64`) and `ContextArgumentClassifier`
   (`ContextArgumentClassifier.java:120`) — both of which assume a single table-bound SQL surface.
   The per-participant carrier is reached by the polymorphic emitter directly (step 4), not through a
   capability interface.
2. **`operation()`: leave it alone for these arms.** The interface/union arms
   (`QueryField.java:46-47`) pass `List.of(), new OrderBySpec.None()`, and they should keep doing so.
   The polymorphic fetcher emit path does **not** consume `operation()`: `TypeFetcherGenerator`'s
   `QueryInterfaceField` / `QueryUnionField` arms (`TypeFetcherGenerator.java:452-472`) call
   `MultiTablePolymorphicEmitter.emitMethods` / `emitConnectionMethods(..., f.participants(), …)`
   directly and never read the `Operation`. `operation()` for these fields is read only by the
   validator's re-fetch consistency check (`GraphitronSchemaValidator.java:164`, via
   `requiresReFetch()`), which keys on operation/target/source *shape*, not on the filter-list
   *contents* — so the per-participant filters do not belong on it. Threading them through
   `operation()` would be inert (the emitter ignores it) and is impossible as a single list anyway
   (`OutputField.readOperation` takes one `List<WhereFilter>`, and the filters are per-participant).
   This is the same "don't carry a surface the emitter ignores" rule the Scope section applied to cut
   `orderBy`. Filters reach the emitter solely via the new parameter in step 4. (Confirm the
   `requiresReFetch()` derivation for these arms is unchanged by step 1's added carrier, so the
   validator's re-fetch consistency check still passes.)
3. **Builder: lower the arguments per participant; reject `@condition`.** In `FieldBuilder`
   (interface/union arms at `FieldBuilder.java:3441-3450`), for each table-bound participant call
   `resolveTableFieldComponents(fieldDef, participant.table(), elementTypeName, …)`, collect the
   per-participant filters, and surface any participant's `Rejected` as the field's rejection (a
   column absent from one participant, or a type mismatch, fails the build naming the participant).
   Watch the side effects of running `resolveTableFieldComponents` N times: it also emits the
   `@asConnection` same-table `@nodeId` advisory warning, so guard against emitting that warning once
   per participant (lower it once, or dedupe).

   **Reject `@condition` *before* the per-participant loop, not after.** If the field carries a
   `@condition` (field-level `fieldDef.hasAppliedDirective(DIR_CONDITION)` or any argument-level
   `arg.hasAppliedDirective(DIR_CONDITION)`), return
   `Rejection.structural("@condition on a multitable interface/union is not yet supported")` — a
   non-deferred kind, no slug (see the `@condition` design decision above) — as an early return at the
   top of the arm. This ordering is load-bearing: `resolveTableFieldComponents` *itself* lowers a
   `@condition` into a `ConditionFilter` bound to whatever table it is handed, so calling it once per
   participant before the guard would silently produce N `ConditionFilter`s, each pinning the
   developer's single-table method (e.g. `cond(FeideApplikasjon, …)`) to a *different* participant
   table — which fails at consumer compile instead of giving the clean rejection. Guarding after the
   loop reintroduces exactly the wrong-table lowering the rejection exists to prevent. Keep this
   rejection on the classify side (FieldBuilder), not bolted into the validator, consistent with where
   the multitable participant invariants already live.
4. **Emitter: thread each participant's filters into its own UNION branch.** The non-connection branch
   loop (`MultiTablePolymorphicEmitter.buildStage1Block`, ~`:872-910`) already ANDs in
   `branchParentFkWhere` (~`:927-951`); the connection branch loop (`buildStage1ConnectionBlock`,
   ~`:807-854`) currently emits no per-branch `.where(...)` at all and needs one added. The emitter
   entry points (`emitMethods` / `emitConnectionMethods`) take `participants` but no filters today, so
   add a per-participant-filters parameter and pass it from the call sites in
   `TypeFetcherGenerator.java` (~`:452-475`, which currently pass `f.participants()`). Extend each
   branch loop (or add a sibling `branchFilterWhere`) to AND each participant's lowered filters into
   its `stage1_<Type>` branch `.where(...)`, combined with the existing parent-FK predicate via
   `.and(...)`. Each participant's filters were generated against its own table, so they bind cleanly
   to that branch's alias — the same way `branchProjection` / `branchParentFkWhere` resolve columns
   today.

## Tests

- Pipeline tier: a `@field`-mapped filter input on a root `QueryInterfaceField` / `QueryUnionField`
  classifies into a model carrying a per-participant predicate for each participant.
- Pipeline tier (rejection): a filter naming a column absent from (or type-incompatible on) one
  participant fails classification with a typed rejection naming that participant and column.
- Execution tier: the query applies `WHERE <col> IN (...)` per branch and returns only matching rows.
  Cover **both** emit paths, since they start in different states: the non-connection list/interface
  path (`emitMethods` / `buildStage1Block`, which ANDs the filter into an existing per-branch
  `.where(...)`) and the `@asConnection` path (`emitConnectionMethods` / `buildStage1ConnectionBlock`,
  which today emits no per-branch `.where(...)` and gains a brand-new one). The connection path is the
  net-new emit and the higher-risk of the two.
- Validation: a `@condition` on a multitable interface/union field is rejected at build (this also
  covers the previously-silent non-existent-method case, since any `@condition` on the path rejects).
  The test asserts the rejection *kind* (`structural`), not merely that some rejection fires, so the
  no-dangling-slug decision is pinned.

## Implementation (shipped, In Review)

All four steps landed as planned. Files: `model/ParticipantFilters.java` (new field-local carrier),
`model/QueryField.java` (new `participantFilters` component on `QueryInterfaceField` /
`QueryUnionField`), `FieldBuilder.java` (per-participant lowering + early `@condition` structural
reject before the loop + `@asConnection` advisory deduped across participants),
`TypeConditionsGenerator.java` (walks the polymorphic fields' per-participant filters),
`MultiTablePolymorphicEmitter.java` + `TypeFetcherGenerator.java` (thread a typename-keyed filter
map into both branch loops). Tests: `MultiTableFilterLoweringTest` (pipeline: per-participant
lowering for interface + union; absent-column rejection; field- and arg-level `@condition` rejected
as `structural`, not deferred) and `MultiTableFilterExecutionTest` (execution: list + `@asConnection`
forms, filter ANDed per branch, only matching rows returned). Full `install -Plocal-db` green.

Two mechanics the plan's steps under-specified, both serving the plan's stated per-participant design
(surfaced during implementation):

- **Step 3 passes `participant.typeName()`, not `elementTypeName`, to `resolveTableFieldComponents`.**
  The generated conditions class is `<returnTypeName>Conditions` (`FieldBuilder.java:1479`); passing
  the interface/union `elementTypeName` would collide all participants on one
  `<Interface>Conditions.<field>Condition` with conflicting table-param types. `participant.typeName()`
  yields the per-participant `FeideApplikasjonConditions` the design section already named.
- **`TypeConditionsGenerator` is wired to the polymorphic fields.** It discovers work via
  `SqlGeneratingField.filters()`, which these fields (correctly) do not implement, so it now also
  walks `QueryInterfaceField` / `QueryUnionField` `participantFilters` and emits one composer method
  per participant. The `QueryConditions` env-adapter is not on the branch path (the emitter calls the
  composer directly via `FkTargetConditionEmitter.emitTerm`), so only this one generator needed wiring.

## Cross-links

Shares `MultiTablePolymorphicEmitter` with R366 (list-cardinality polymorphic split-query emit).
R382 (orderBy lowering on the same fields) is the split-off follow-up for the ordering surface this
item scopes out.

## Spec-review revisions (2026-06-24)

Reviewer (Spec gate, session ≠ author) resolved the original draft's open design fork rather than
carrying it into Ready: filters are lowered **per participant**, which makes the absent-column case a
classifier rejection (not a runtime scope-narrowing or a separate validator pass), folds column-type
compatibility into the same per-participant binding, and scopes `@condition` out of day one with a
typed rejection in place of the silent drop. A fresh session must still sign this off to Ready.

## Spec-review revisions (2026-06-25)

Second reviewer (Spec gate, session ≠ author/last committer) made three changes, corroborated by a
`principles-architect` read:

- **Cut `orderBy` to a follow-up (R382).** It was in day-one scope (Steps 1–2) but contradicted by
  the emitter the plan points at: `__sort__` is the participant PK and, on the connection path, the
  Relay cursor seek key. Step 4 described only filter threading and the Tests section had no orderBy
  test, so the slot would have been carried-but-dropped, the very gap this item fixes. Day one is now
  `@field` filters only, matching the reported defect; R382 owns the `__sort__`/cursor reconciliation.
- **`@condition` rejection is now non-deferred (`structural`), no slug.** The original note ("file the
  follow-up first so the slug resolves") managed a real hazard by prose: nothing in the build pins a
  deferred `planSlug` to a roadmap file (shipped slugs already dangle), and the referenced `R367` is
  not a live item. A `structural` rejection removes the dangling-pointer risk entirely, and the
  `@condition` test now asserts the rejection kind.
- **Implementation notes added:** prefer co-locating filters on `ParticipantRef.TableBound` over a
  stringly `Map`; guard against running `resolveTableFieldComponents` per participant re-emitting the
  `@asConnection` advisory N times; the emitter entry points need a new filters parameter threaded
  from `TypeFetcherGenerator`'s call sites; drifted line references refreshed against current source.

A fresh session must still sign this off to Ready (this reviewer's substantive edits disqualify it
from the approval).

## Spec-review revisions (2026-06-25, second pass)

Third reviewer (Spec gate, session ≠ author/last committer) traced the model and generator wiring and
corrected two single-table-shaped surfaces the plan proposed to reuse on the polymorphic fields:

- **Steps 1+2: drop `SqlGeneratingField` and the `operation()` filter threading.** Both are
  single-table surfaces that do not fit the polymorphic model. `SqlGeneratingField.returnType()`
  returns `TableBoundReturnType`, but `QueryInterfaceField` / `QueryUnionField` carry
  `PolymorphicReturnType` (a sibling variant), so `implements SqlGeneratingField` does not compile;
  its flat `filters()` also contradicts the per-participant decision, and implementing it opts these
  fields into `TypeConditionsGenerator` / `ContextArgumentClassifier`, which assume a single
  table-bound surface. Separately, the polymorphic fetcher emit path (`TypeFetcherGenerator` →
  `MultiTablePolymorphicEmitter.emitMethods` / `emitConnectionMethods`) reads `f.participants()`
  directly and never consumes `operation()`; threading filters there is inert and impossible as a
  single list. Filters reach the emitter solely via the step-4 parameter; `operation()` stays
  `List.of(), new OrderBySpec.None()`. This is the same "don't carry a surface the emitter ignores"
  rule the Scope section used to cut `orderBy`.
- **Tests: execution coverage names both emit paths.** The non-connection (`buildStage1Block`, ANDs
  into an existing branch `.where`) and `@asConnection` (`buildStage1ConnectionBlock`, gains a
  brand-new branch `.where`) paths start in different states; the connection path is the net-new emit.

A fresh session must still sign this off to Ready (this reviewer's substantive edits disqualify it
from the approval).

## Spec-review revisions (2026-06-25, third pass)

Fourth reviewer (Spec gate, session ≠ last committer) reviewed the third pass's corrections (verified
sound against source) and tightened two implementation details before handing off:

- **Step 3: reject `@condition` *before* the per-participant loop, not after.**
  `resolveTableFieldComponents` itself lowers a `@condition` into a `ConditionFilter` bound to the
  table it is handed, so running it once per participant before the guard would silently emit N
  `ConditionFilter`s, each pinning the developer's single-table method to a different participant
  table (a consumer-compile failure, not the intended clean rejection). The guard is now specified as
  an early return keyed on field- or argument-level `DIR_CONDITION`.
- **Step 1: carrier is field-local, not a component on `ParticipantRef.TableBound`.** The prior nudge
  ("co-locate on each `ParticipantRef.TableBound`") loaded a per-field concern onto a type-scoped,
  shared model type (`participants()` comes from the cached interface/union verdict; the same
  interface backs multiple fields), which would force empty-list threading through every other
  `TableBound` construction site and diverge from `QueryTableInterfaceField`'s separate field-level
  `filters` precedent. Now specified as a small field-local `(participant, filters)` pairing record.

A fresh session must still sign this off to Ready (this reviewer's substantive edits disqualify it
from the approval).
