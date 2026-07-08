---
id: R451
title: "Routine writes: @routine on Mutation commits before the follow-up query"
status: Spec
bucket: feature
priority: 2
theme: service
depends-on: [routine-chain-classification-edges]
created: 2026-07-08
last-updated: 2026-07-08
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
pointed here rather than at a dead end. This item builds on R449 (`depends-on`): D1's
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
only.** Statement 1 executes the routine inside the per-field transaction and captures only
the columns that hop 0's name-matched key needs from the routine's result rows (the exact
analog of DML's PK-only `RETURNING` keys). Statement 2 runs after the commit: a read-only
SELECT anchored on the first hop's table with `WHERE <hop-0 key> IN <captured values>`,
remaining hops joined as in R435, projecting the terminus type. The routine never appears in
statement 2's `FROM`; re-invoking it would re-execute the write. This answers the
return-shape question: the field's return binds to the follow-up re-query only, never to the
routine's own rows or OUT parameters, so the pinned contract (the re-read observes committed
state) holds without exception rather than as a per-shape convention.

**D2: Slice scope is table-valued routines in chain form; everything else gets a typed
`Deferred`.** Accepted: `@routine` plus at least one `@reference` hop on a Mutation field,
the routine resolving through the existing `JooqCatalog.resolveTableValuedFunction` (a
`VOLATILE` set-returning function is already a table in the jOOQ catalog; no new call
surface is needed), terminus equal to the field's `@table` type under the same chain
verdicts as R435. Deferred, each with a typed `Deferred`: true procedures and scalar / void
routines (jOOQ exposes them through a different call surface, static `Routines` methods with
`Configuration` and OUT-parameter getters, whose full resolution is the follow-up's work),
and the single-node `@routine` on Mutation (with no hop there is no post-commit table to
re-read from, so its story belongs with the void / OUT-parameter result-shape work).

Two consult-driven refinements pin how those `Deferred`s stay honest. First, the current
`RoutineResolution.NotInCatalog` covers both a genuinely absent name and a procedure /
scalar routine (jOOQ does not place those in `getTables()`), so the classifier could not
tell a procedure name from a typo; this item adds a `RoutineResolution` arm (for example
`NotATableValuedRoutine`) that probes the schema's routines for a same-named routine that
is not table-valued, making "exists but is not table-valued" a distinct fact from "absent". The
typed `Deferred` routes off that arm; genuinely absent names keep the structural
not-in-catalog rejection. Second, the follow-up Backlog item
(`routine-write-result-shapes` or similar) is filed in the same change that repoints the
single-node and procedure `Deferred` planSlugs at it, so the emitted signpost never points
at a roadmap item that does not exist (the same discipline R449 showed by minting
`routine-mutation-write` while this item was live). The multi-node chain `Deferred` from
R449's D1 is the one this item replaces with real classification.

**D3: Model and classification.** A new leaf `MutationField.MutationRoutineWriteField` with
`source() = Source.Root.Mutation`; this is the "procedure-write `Operation` write arm"
R300's retirement anticipated in the R316 dimensional model, a new operation on an existing
source rather than a new source (the established convention across the service family: one
sealed leaf per root hierarchy, `source()` fixed by membership). The chain shape is shared,
not copied: a `RoutineChain(TableExpr.RoutineCall start, List<JoinStep> hops)` value record
enforces the shared invariants once (every start binding `ParamSource.Arg` per R449 D5's
pin, hops are catalog targets, terminus matches the `@table` return), embedded by both
`QueryRoutineTableField` and the new leaf and exposed through a `RoutineChainField`
capability interface (the `ServiceField` precedent, one accessor spanning the Query and
Mutation leaves), so R448's `DataType` lift or any future chain-invariant change edits one
enforcer. The write leaf adds its one extra pin, `hops` non-empty (D2's chain-form
requirement); the read leaf keeps admitting the R300 single-node degenerate chain.
Classification: the R449 D1 interception arm for Mutation root chains routes to a
`classifyMutationRoutineChain` landing the new leaf instead of minting the `Deferred`; the
single-node check at the top of `classifyMutationField` stays a typed `Deferred` but its
planSlug moves to the follow-up item. The validator mirrors the classifier through the
existing `UnclassifiedField` projection; the leaf joins `IMPLEMENTED_LEAVES` (needing no
`STUBBED_VARIANTS` entry because it ships implemented), which
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces
mechanically.

**D4: Emission, commit ownership, and errors.** A two-step fetcher in
`TypeFetcherGenerator` mirroring the DML `emitKeysTransaction` / `emitProjected` split: step
1 is `keys = dsl.transactionResult(tx -> DSL.using(tx).select(<hop-0 key columns>)
.from(<routine table call>).fetch(...))`, with the routine call emitted through the shared
`RoutineCallEmitter.emitCall` (all-`Arg` bindings, so the uncorrelated value overload) and
the commit happening when the lambda returns; step 2 is the post-commit chain SELECT with
hops through `JoinPathEmitter`, both emitters reading the chain off the `RoutineChainField`
capability rather than either concrete leaf. The split is a mirror of the DML shape, not
helper reuse as-is: `emitKeysTransaction` / `buildPkKeysCondition` are keyed on the table's
primary-key columns and build a DML `RETURNING`, while step 1 here is a SELECT from the
routine keyed on the hop-0 name-matched key; `buildPkKeysCondition` generalizes to take an
arbitrary key-column list so the `key IN captured` condition is shared rather than
re-emitted. This answers the commit-ownership question: the commit is
graphitron-managed, identically to DML, riding R429's per-field `transactionResult` boundary on the
writable acquisition handle; commit-vs-rollback policy stays provider-global and is never
site-declared. Error mapping follows DML exactly: an SQL error from the routine rolls the
transaction back at the `transactionResult` boundary and surfaces on the mutation field
through the same channel as DML errors, and a read error in step 2 cannot undo the
already-committed write (the same documented caveat the DML fetchers carry).

**D5: Fixture routine and coverage.** `graphitron-sakila-db` has no writing routine (both
existing functions are `STABLE` reads), so `init.sql` gains a `VOLATILE` set-returning write
function (a jOOQ schema version bump), for example `public.rent_film(p_inventory_id INT,
p_customer_id INT) RETURNS TABLE(rental_id INT)` inserting into `rental`, chained
`@routine @reference` to re-read the created rows. The legacy generator's 26 `procedureCall*`
rejection fixtures are not in this repo (the count survives only in prose), so coverage is
reconstructed as this slice's own acceptance and deferral arms rather than translated
file-by-file; auditing against the legacy repo is a separate pass if ever needed, and the
R300 deferral clause's real ask (pipeline coverage for the rejection arms) is satisfied by
the tests below.

## Tests

* Pipeline tier: the accepted Mutation chain lands `MutationRoutineWriteField` with the
  pinned `RoutineChain` shape; a single-node Mutation `@routine` lands the typed `Deferred`
  with the follow-up planSlug; a procedure or scalar-function name on a Mutation chain
  lands the typed `Deferred` routed off the new `RoutineResolution`
  exists-but-not-table-valued arm, while a name absent from the catalog stays the structural not-in-catalog
  rejection (the fixture pair that pins the D2 distinction); directive conflicts are R449
  D2's coverage and are not re-asserted here. New fixtures land as `ClassifiedCorpus`
  entries on Mutation coordinates, not ad-hoc `GraphitronSchemaBuilderTest` blocks.
* Compile tier: the generated fetcher pins the two-step shape, the routine call inside
  `transactionResult` and the chain SELECT outside it.
* Execution tier: running the sakila write mutation returns the rows the routine created,
  observed through the post-commit follow-up query (the row exists in the response and in a
  subsequent independent read), and a failing routine call surfaces an error with no row
  committed.

## Out of scope

* Procedures proper (the static `Routines` call surface, OUT parameters), void and scalar
  routines, and the single-node Mutation `@routine` result shape: all carried by the
  follow-up item D2 files at landing.
* The read-side scalar-function fork deferred at R300, Subscription routines, and
  child-positioned writes (writes are root-only by construction).
* R429's provider and lifecycle implementation itself; this item only rides its shipped
  per-field `transactionResult` contract.
