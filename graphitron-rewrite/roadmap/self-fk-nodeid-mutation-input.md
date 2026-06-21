---
id: R328
title: "Self-FK @nodeId reference on Graphitron-owned DML mutation inputs"
status: Ready
bucket: feature
priority: 4
theme: nodeid
depends-on: []
created: 2026-06-18
last-updated: 2026-06-21
---

# Self-FK @nodeId reference on Graphitron-owned DML mutation inputs

A same-table `@nodeId` that is semantically a self-referencing FK cannot be expressed
today. `NodeIdLeafResolver.resolve` short-circuits to `Resolved.SameTable` (own-PK
identity) the moment the `@nodeId` target table equals the containing table (line 269),
*before* `@reference` is ever consulted. So there is no way to say "this field points at
a **different** row of the same table via a self-FK"; the decoded id would be written into
the row's own key columns, which is wrong.

This item lets an explicit `@reference(path: [{key: ...}])` on a same-table `@nodeId` mean
"follow this self-FK and write its child columns" instead of "use my own identity",
symmetric with the read side. It is the "separate design item" R315 deferred self-reference
to.

Since this was filed, two siblings landed and shrank it dramatically: **R315** (FK-reference
`@nodeId` on jOOQ-record `@service` params) and **R322** (runtime value-agreement + structural
dedup for multiple `@nodeId` decodes onto shared columns). R322 already does all the
emit-side work this item once owned (see D3). What remains is two small classifier changes
plus tests.

## Motivating consumer

`utdanningsregisteret-graphql-spec` `opprettCampus`:

```graphql
input OpprettCampusInput @table(name: "CAMPUS") {
  larestedId: ID! @nodeId(typeName: "Larested")    # cross-table FK, admitted (R189)
  kode: String! @field(name: "CAMPUSKODE")
  eierCampusId: ID @nodeId(typeName: "URegCampus")  # SELF-FK to parent campus, rejected today
}
```

`URegCampus` is `@node(keyColumns: ["organisasjonskode","campuskode"]) @table(name: "CAMPUS")`;
the self-FK `CAMPUS__CAMPUS_EIER_CAMPUS_FK` is `(organisasjonskode, campuskode_eier) ->
(organisasjonskode, campuskode)`. The read side already disambiguates the same self-FK with
`eierCampus: URegCampus @reference(path: [{key: "CAMPUS__CAMPUS_EIER_CAMPUS_FK"}])`; the write
side has no honored equivalent.

The campus and its parent always share an institution, so the self-FK's first child column
`organisasjonskode` coincides with the column `larestedId` writes: two writers, one column.
That overlap used to be the hard part; R322 now reconciles it (D3).

The blocked upgrade does not wait on this item; the consumer unblocks now by routing
`opprettCampus` through a `@service` (its neighbors already do). This item lets the schema
later drop the `@service` and express `eierCampusId: ID @nodeId(typeName: "URegCampus")
@reference(path: [{key: "CAMPUS__CAMPUS_EIER_CAMPUS_FK"}])` directly.

## Design

### D1. Classifier gate (`@mutation` and read paths) — the one substantive change

Gate the `NodeIdLeafResolver.resolve` line-269 same-table short-circuit on the **absence** of
`@reference`. With `@reference` present, fall through to the existing `resolveFkJoinPath`,
which already passes `selfRefFkOnSource=true` and resolves the self-FK; the result is a
`Resolved.FkTarget.DirectFk` whose `liftedSourceColumns` are the self-FK's child columns on
the row's own table, wrapped as the R189 `ColumnReferenceField` / `CompositeColumnReferenceField`
carriers. **No new sealed variant**: a same-table self-FK and a cross-table FK carry the same
data shape (decoded keys -> child columns via `liftedSourceColumns`); the only thing that
varies (same-table vs cross-table orientation) is already owned by `selfRefFkOnSource` inside
the shared `resolveFkSlots` core (`BuildContext:1258`).

**This also admits the shape on the read side, by design.** `resolve()` is shared by the
write-side input-field classifier (`BuildContext.classifyInputField`), the read-side input-field
filter arm, and the top-level `@nodeId` argument arm (`FieldBuilder.classifyArgument`). Today
line 269 swallows a same-table `@reference` for *all* of them. Relaxing it means a same-table
`@nodeId @reference` on a **query filter / argument** newly resolves to `DirectFk` and is
admitted as a self-FK filter (`WHERE child_cols IN (decoded keys)`, no self-join). This is
correct and desirable, and it composes cleanly: a `DirectFk` is a filter, so the
`@asConnection` same-table advisory correctly stops firing (`FieldBuilder:637`,
`NodeIdLeafResolver:44-48`). It is **in scope** and must be covered by a read-side test, not
left as an unstated side effect of where the gate lives. Keeping the single gate in `resolve()`
(rather than duplicating it into the write callers) preserves the "resolve the self-FK decision
once" property.

### D2. Lift the `@service` jOOQ-record reject

`InputBeanResolver.buildRecordKeyDecode` (`:471-493`) currently rejects same-table + `@reference`
("self-reference record population is out of scope"). Drop that reject; route the case to the
same `resolveRecordFkTargetColumns(...)` the cross-table branch already calls. That helper
already orients a self-FK correctly via `resolveFkSlots(..., selfRefFkOnSource=true)`
(`BuildContext:2347`), mapping the node-key columns through the self-FK to its child columns on
the record. Update the now-false "selfRefFkOnSource is moot" comment at `BuildContext:2370-2372`
— it becomes live the moment the `@service` path admits a self-FK (record table == node table).

### D3. Emit and shared-column overlap — R322's, not this item's

R322 already built a carrier-agnostic per-column overlap analysis on the write paths, so the
self-FK overlap flows through with **no emitter code in R328**. Verified end to end on trunk:

- `TypeFetcherGenerator.insertColumnPlan` (`:2275`) walks `setFieldColumns(sf)` for every
  `SetField` leaf — including the reference carriers' `liftedSourceColumns()` — and dedups any
  column written by >= 2 carriers to one column / one coalesced cell, emitting a
  `NodeIdEncoder.requireColumnAgreement` runtime check (presence-guarded). Covers single-row and
  bulk INSERT.
- `emitSetAgreementPreamble` (`:2698`) is the single-row UPDATE SET sibling (same predicate,
  carrier-agnostic over `SetGroup.columns()`).
- The build-time reject `MutationInputResolver.rejectPlainColumnCollision` (`:622`) fires only on
  **all-plain** collisions (`paths.size() >= 2 && !columnsWithDecode.contains(column)`). The
  self-FK overlap has a `@nodeId` decode on both writers, so it is admitted and reconciled at
  runtime — not rejected.
- The `@service` path has the same reconciliation in `JooqRecordInstantiationEmitter`.

So the self-FK reference carrier writes its child columns from `liftedSourceColumns` (never a
self-join), and the shared `organisasjonskode` is deduped + agreement-checked by R322's code.

### D4. Cross-path consistency — pin by test, not by a new abstraction

D1 and D2 leave "same-table + `@reference` => self-FK, not identity" as a one-line predicate at
two sites. That is the "same predicate, two consumers" shape Generation-thinking warns about, but
the drift surface here is narrow: the FK *orientation* is already shared (`resolveFkSlots`), R322
owns the shared *agreement* predicate, and the per-path overlap analysis is an explicitly
documented R342 residual. Adding a third shared abstraction would be over-modeling. Instead, pin
the invariant with a test asserting **both paths admit the identical self-FK SDL shape** (the
CAMPUS/email mirror) and **both treat a same-table `@nodeId` *without* `@reference` as identity**.
(A thin shared `BuildContext.isSelfFkReference(leaf, containingTable)` both sites call is an
acceptable alternative if a future reviewer prefers code over a test; not required here.)

## Scope and boundaries

- **In scope:** self-FK `@nodeId @reference` on `@mutation` INSERT and single-row UPDATE inputs,
  on `@service` jOOQ-record params, and (falling out of the shared gate, D1) as a read-side query
  filter / argument.
- **Bulk UPDATE SET is R342's loud gap.** A self-FK shared-column overlap on a `multiRow: true`
  UPDATE hits the one surface R322 left unfinished: the bulk SET `v(...)` derived-table builder
  has no cross-carrier dedup, so a duplicate column **crashes loudly at runtime** (duplicate
  derived-table column), not silently. This is the *same* gap a cross-table FK reference + plain
  `@field` overlap already reaches (R342's motivating shape), not a new one R328 introduces, and a
  loud-not-silent interim that R322's review explicitly accepted. R342 owns the fix (it implements
  the bulk-SET dedup; it does not add a validate-time reject). R328 makes the gap reachable via a
  *natural* self-FK shape rather than a contrived one, which is a mild forcing-function nudge for
  R342's priority — worth noting, not worth blocking on.
- **SDL surface:** `@reference(path: [{key: ...}])`. Both the `@mutation` (`parsePath`) and
  `@service` (`firstReferenceKey`) parsers already consume it; the mooted `@reference(key:)`
  shorthand was never built. Read/write symmetric.

## Tests

- **`@mutation` classifier** — *add cases to the existing* `MutationDmlNodeIdClassificationTest`:
  same-table `@nodeId @reference` admits as a `CompositeColumnReferenceField` over the self-FK
  child `liftedSourceColumns` (e.g. `(mailbox_id, in_reply_to_no)`), surfacing the shared-column
  overlap; contrast a same-table `@nodeId` *without* `@reference` still classifying as identity.
- **`@service` classifier** — a `JooqRecordServiceParamPipelineTest` case: same-table
  `@nodeId @reference` admits, the `RecordKeyDecode` targets the self-FK child columns (not the
  record's own PK).
- **Read-side filter (D1)** — a pipeline case: a same-table `@nodeId @reference` query argument /
  filter resolves to a `DirectFk` filter (no self-join), and the `@asConnection` same-table
  advisory does not fire.
- **Cross-path anti-drift (D4)** — one test asserting both paths admit the identical self-FK shape
  and both treat no-`@reference` same-table as identity.
- **Execution tier** — a self-FK INSERT round-trip proving the end-to-end shape works through
  R322's dedup + agreement: agreement on the shared column inserts the agreed value (no
  column-twice crash), disagreement throws and inserts nothing, an omitted nullable self-FK leaves
  the lone decode.

## Fixture

Needs a composite-PK table with a self-FK whose child columns partly overlap a cross-table FK's
child columns (the CAMPUS shape, neutral form): the `email` / `mailbox` pair — `email`
composite-PK `(mailbox_id, message_no)`, self-FK `email_in_reply_to_fk
(mailbox_id, in_reply_to_no) -> (mailbox_id, message_no)`, plus cross-table
`email.mailbox_id -> mailbox`. Confirm the current fixture-codegen mechanism on trunk (R322 added
its overlap fixtures to the sakila-example set; reuse that path or the nodeid fixture set as
appropriate) and bump the jOOQ schema version so the catalog regenerates.

## Coordinates with

- **R315** (Done): the sibling `@service`-record FK-reference path. The same-table-`@reference`
  reject this item flips lives today at `InputBeanResolver.buildRecordKeyDecode:471-493`.
- **R322** (Done): the shared `requireColumnAgreement` predicate and the carrier-agnostic INSERT /
  single-row UPDATE SET dedup + agreement that this item rides on (D3).
- **R342** (Backlog): the bulk UPDATE SET decode-overlap dedup; the one boundary where a self-FK
  shape still fails loud (above).
- **R189** (FK-target reference carriers on INSERT/UPDATE/DELETE) and **R130** (the INSERT
  `CompositeColumnField` carve-out, which the reference carrier sidesteps — `MutationInputResolver:523`
  gates only `CompositeColumnField`, not the reference carriers).
