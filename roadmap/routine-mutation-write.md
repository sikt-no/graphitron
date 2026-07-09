---
id: R451
title: "Routine writes: @routine on Mutation commits before the follow-up query"
status: In Review
bucket: feature
priority: 2
theme: service
depends-on: []
created: 2026-07-08
last-updated: 2026-07-09
---

# Routine writes: @routine on Mutation commits before the follow-up query

`@routine` is read-only today: R300 shipped the day-one table-valued read slice on Query and
R435 extended it to order-significant chains, but the procedure-write fork was deferred at
R300's retirement and survives only as prose in its `changelog.md` entry — no live item
carries it. An SDL author cannot back a Mutation field with a database routine, which is a
core capability: the legacy generator supported the shape via `@experimental_procedureCall`
(whose 26 `procedureCall*` rejection fixtures R300 also left untranslated).

The contract this item exists to pin: **the routine call is the write, and it commits before
any follow-up query runs.** `type Mutation { x: [Film!] @routine(...) @reference(...) }`
means: execute the routine, commit its transaction, then run the chain's follow-up query so
the re-read observes committed state. That is a structurally different emission from R435's
read chain, which renders one SQL statement (the routine as the `FROM` source, hops joined
laterally); write-then-requery is two statements with a transaction boundary between them,
so the emitter shape is new and the commit placement interacts with the connection /
transaction lifecycle hooks (R429).

Classification state at filing: a single-node `@routine` on Mutation lands
`classifyMutationField`'s generic "needs `@service` / `@mutation`" fallback, and a multi-node
chain misclassifies as a Query read until R449 lands. R449's D1 mints the typed `Deferred`
with this item's planSlug (`routine-mutation-write`) for both shapes, so the SDL author is
pointed here rather than at a dead end. This item builds on R449 (shipped): D1's
Query-only interception gate and Deferred signposts are the classification seam this item
opens, and D5's consolidation of the root routine call onto `RoutineCallEmitter` /
`JoinPathEmitter` is the shared emission surface the write fetcher reuses rather than adding
a third hand-built call site.

## Design

Shaped with the principles consult (2026-07-08): the procedure-vs-absent distinction gets
its own `RoutineResolution` arm so the typed `Deferred` is routable at all, the chain shape
and its pins are shared with the read leaf through a capability interface rather than
duplicated, and the follow-up item is filed atomically with the planSlug repoint.

The load-bearing observation: the two-statement write-then-requery shape this item needs is
the shipped DML pattern, not new ground. `TypeFetcherGenerator`'s DML fetchers already emit
step 1 as `dsl.transactionResult(tx -> ...)` with a PK-only `RETURNING` (the transaction
commits when the lambda returns) and step 2 as a separate post-commit SELECT hydrating the
GraphQL selection, and R429 pins that `transactionResult` call as the per-mutation-field
transaction boundary. The write arm substitutes a routine call for the DML verb in step 1
and the R435 chain walk for the PK re-read in step 2; commit placement, error routing, and
transaction ownership are all inherited, not invented.

**D1: The response always re-reads committed state; the routine result is a key carrier
only.** Shipped at 3ce199b (`TypeFetcherGenerator.buildMutationRoutineWriteFetcher`;
`RoutineMutationWritePipelineTest` pins the two-step shape and that the routine executes
exactly once, inside the transaction).

**D2: Slice scope is table-valued routines in chain form; everything else gets a typed
`Deferred`.** Shipped at 3ce199b. The new arm landed as
`RoutineResolution.NonTableValuedRoutine`, populated by a probe of the generated `routines`
sub-package (candidate class via jOOQ's PascalCase transform, verified by instantiating and
comparing `getName()`, so a transform miss degrades to the absent-name rejection, never a
false positive). The follow-up item landed as R454 (`routine-write-result-shapes`), filed
ahead of the planSlug repoint.

**D3: Model and classification.** Shipped at 3ce199b (`RoutineChain` /
`RoutineChainField` in the model package, the terminus rule staying per-leaf because it
reads each leaf's return type; the verb landed as the new `Operation.RoutineWrite` arm and
its `ClassifiedDsl` enum value).

**D4: Emission, commit ownership, and errors.** Shipped at 3ce199b (the shared condition
builder landed as `buildKeysInCondition(conditionCols, keyCols, isList)` with the DML
`buildPkKeysCondition` reduced to a thin wrapper passing its PK triplets on both sides).

**D5: Fixture routine and coverage.** Shipped at 3ce199b (`public.rent_film` exactly as
sketched, plus the scalar `public.rental_count_for_customer` the Tests section's
exists-but-not-table-valued arm needed; schema version 2.8 to 2.9).

## Tests

Shipped at 3ce199b, all three tiers. One placement deviation from the spec text: the
`ClassifiedCorpus` carries the accepted-leaf fixture (`routine-mutation-write`, which also
exercises the new `RoutineWrite` operation arm for the dimension-coverage test), but the
Deferred and rejection pins live in `GraphitronSchemaBuilderTest`'s R451 block, because the
corpus asserts successful classification only (no Deferred/rejection coordinate exists in
the DSL). The two-step fetcher shape pin runs at pipeline tier
(`RoutineMutationWritePipelineTest`, the sanctioned `TypeSpec` fingerprint form) with the
compile tier covered by the sakila-example `rentFilm` field compiling against the real
catalog; execution tier is `GraphQLQueryTest.rentFilm_*` (committed row observed by an
independent read; failing routine rolls back with nothing committed).

## Implementation notes (2026-07-09)

Recorded for the reviewer; all shipped at 3ce199b:

* The classifier gained one write-only verdict the spec did not enumerate: a Mutation chain
  whose hop 0 joins by `condition:` or carries a per-hop filter lands a typed `Deferred`
  (empty planSlug, the R435 precedent) because its predicate references the routine alias,
  which must not appear in statement 2, so no post-commit re-read anchor is derivable.
* The `RoutineResolution` arm is named `NonTableValuedRoutine` (the spec's
  `NotATableValuedRoutine` example read as too close to the existing
  `NotATableValuedFunction`, which means "resolves to a plain table / view").
* The LSP catalog projection reuses `FieldClassification.QueryTableMethod` for the write
  leaf, mirroring the shipped R300 precedent for the read leaf (a dedicated routine
  classification stays a follow-up once the LSP label/hover surface is wired).
* The root-head rule ("move @routine first") and the multi-routine deferral now apply to
  Mutation write chains exactly as to Query chains.
* The `@routine` directive reference gained the "Writes on Mutation" section and the
  constraint updates (non-table-valued names now defer instead of rejecting as unknown).

## Out of scope

* Procedures proper (the static `Routines` call surface, OUT parameters), void and scalar
  routines, and the single-node Mutation `@routine` result shape: all carried by the
  follow-up item D2 files at landing.
* The read-side scalar-function fork deferred at R300, Subscription routines, and
  child-positioned writes (writes are root-only by construction).
* R429's provider and lifecycle implementation itself; this item only rides its shipped
  per-field `transactionResult` contract.
