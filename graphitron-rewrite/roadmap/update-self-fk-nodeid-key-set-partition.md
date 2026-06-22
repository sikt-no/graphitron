---
id: R354
title: "Self-FK @nodeId on UPDATE: classify the self-FK reference as all-SET and agreement-check the shared key column"
status: Ready
bucket: feature
priority: 5
theme: nodeid
depends-on: []
created: 2026-06-22
last-updated: 2026-06-22
---

# Self-FK @nodeId on UPDATE: classify the self-FK reference as all-SET and agreement-check the shared key column

R328 lets an explicit `@reference` on a same-table `@nodeId` mean "follow this self-FK and write its child columns" on a Graphitron-owned DML input. It lands on `@mutation(typeName: INSERT)`, where R322's per-column dedup reconciles a self-FK child column that coincides with a PK column. On `@mutation(typeName: UPDATE)` the same authoring is rejected: when the self-FK's child columns straddle the row's identity key (some columns are PK/UK members, some are not), `UpdateRowsWalker` Stage 6 fails with *"input field '<f>' lifts columns that straddle the matched key: {…} are in the key but {…} are not. A single input field cannot span the WHERE and SET partitions."* (`UpdateRowsError.MixedCarrierKeyMembership`). The straddle check partitions at **input-field granularity** (a whole `@nodeId` field must sit entirely in WHERE or entirely in SET), so a self-FK whose child columns overlap the PK has no expressible UPDATE form. Without the `@reference`, the bare same-table `@nodeId` silently degrades to the own-PK short-circuit, semantically wrong (the field wants a *different* row's id, the parent, not a restatement of the row's own identity), and survives validation only because R322 defers the "two ids on the same columns" conflict to a runtime agreement check. So today UPDATE has no correct authoring for a self-FK that shares a key column.

Motivating consumer: `utdanningsregisteret-graphql-spec` `endreCampus` / `EndreCampusInput.eierCampusId` (`@nodeId(typeName: "URegCampus") @reference(path: [{key: "CAMPUS__CAMPUS_EIER_CAMPUS_FK"}])`). The campus PK is `(organisasjonskode, campuskode)`; the self-FK `CAMPUS__CAMPUS_EIER_CAMPUS_FK` child columns are `(organisasjonskode, campuskode_eier)`. `organisasjonskode` is in the PK (a campus's parent is always in the same organisasjon, so the FK shares it), `campuskode_eier` is not. Adding the `@reference` (correct under R328) trips the straddle check; the INSERT form (`opprettCampus`) accepts the same `@reference` and works.

The neutral in-repo analogue is the existing R328 `email` / `mailbox` fixture: `email` PK `(mailbox_id, message_no)`; self-FK `email_in_reply_to_fk` child columns `(mailbox_id, in_reply_to_no)`; `mailbox_id` shared with the PK, `in_reply_to_no` not. The worked examples below use it.

---

## Design (supersedes the filing's per-column-membership direction)

The filing proposed partitioning the lifted columns by **per-column PK/UK membership**: route the in-key child column to the WHERE (reconciled against the row identity), the out-of-key columns to SET. That reasons from physical column overlap with the PK, which is the wrong axis. The right axis is the **field's role**. A self-FK reference field is a write of "who this row points at" (its parent). Its child columns are a pointer to a sibling row, never this row's own identity. A column does not get reclassified as identity (WHERE) just because the FK happens to share it with the PK.

So: **a self-FK reference field contributes its lifted columns to SET, all of them, whole.** Row identity (WHERE) comes only from identity-eligible fields (own `@nodeId`, plain `@field`s on key columns, cross-table FK references). The shared key column then appears in *both* partitions, the WHERE (from the identity field) and the SET (from the self-FK), which is ordinary SQL:

```sql
UPDATE email
   SET mailbox_id = ?, in_reply_to_no = ?, subject = ?
 WHERE mailbox_id = ? AND message_no = ?
```

This deletes the hard part of the filing's design. Because the self-FK carrier stays whole in SET in its natural lifted-column order, the existing positional decode-slot derivation (`setGroupsOf` reads `value(ci+1)` from the column's index within its group) stays correct. No explicit-slot model surgery, no splitting a carrier across SET and WHERE.

### Why self-FK specifically, not all reference carriers

The all-SET rule is gated on self-FK and must not extend to cross-table FK references, because cross-table FK child columns *can* legitimately be the row's own identity. `MutationDmlNodeIdClassificationTest.fkTargetNodeIdRef_arity1_update_admitted` pins this: `bar` has PK `(id_1, id_2)`, and `updateBar` identifies the row by `bazRef: @nodeId(typeName: "Baz")` (a cross-table FK whose lifted child column `id_1` *is* a PK member) plus `id2`. `bazRef`'s column is routed to the **WHERE**, correctly: `bar` is a weak entity whose identity includes a FK to its parent. Identifying a junction / weak-entity row by its FK pair is a real, tested pattern that must not regress.

The distinction is semantic, and the worked prose should keep it visible:

- **Same-table (self) FK reference** (`inReplyTo`, `eierCampusId`): the shared child column is *FK-forced* equal to the row's own corresponding column (`email_in_reply_to_fk` makes `child.mailbox_id` the row's own `mailbox_id`; a reply lives in its parent's mailbox). Its columns are a pointer to a sibling. → all SET.
- **Cross-table FK reference** (`bazRef`): a lifted column that overlaps the PK does so by *coincidence of catalog shape*, and the column can be the row's own identity. → partition by key membership, as today.

### The shared key column: a cross-partition agreement

When the shared key column sits in both the WHERE (from the identity field) and the SET (from the self-FK), the two decoded values must agree, the FK constraint *forces* them equal for any well-formed input. This is the same "no silent drops" invariant R322 already enforces, extended to a boundary R322 never crossed: **its four agreement sites all gather writers within one clause** (an INSERT's `VALUES`, a single-row UPDATE's `SET`). The self-FK UPDATE is the first overlap that straddles SET and WHERE, so it cannot piggyback on the existing SET-only `emitSetAgreementPreamble`.

R354 emits a cross-partition agreement preamble for the single-row UPDATE: a column appearing in both `keyColumns` and `setColumns` is decoded on both sides (presence-guarded, into self-contained preamble locals, the deliberate extra decode `emitSetAgreementPreamble` already establishes the precedent for) and passed through `NodeIdEncoder.requireColumnAgreement` (reused unchanged, coerces both sides through the column `DataType`, throws `GraphqlErrorException` on disagreement) **before** the DML runs. The throw names both contributing input fields (the identity field and the self-FK field), mirroring `emitInsertAgreementPrep`'s label. On agreement the SET write of the shared column is provably a no-op (it equals the WHERE value); it stays in the SET map deliberately (the author's "the self-FK writes all its columns" intent) and the agreement makes it safe.

**Rejected alternative.** Emitting a second WHERE predicate (`… AND mailbox_id = <self-FK decode>`) instead of an agreement check looks simpler but is a silent drop wearing a no-match costume: two independently-satisfiable equalities mean a disagreement narrows the result to zero rows ("no row matched") rather than surfacing the inconsistency. The agreement *throw* is mandatory; "pin from identity and ignore the self-FK's slot for that column" is also rejected, since the decode produces the whole `Record<N>` and discarding the shared slot's value would drop a disagreement unobserved.

### Key coverage comes from identity columns

`UpdateRowsWalker` matches the WHERE key (`MatchedKeys.firstCovered`, PK preferred) over the input-covered columns. R354 computes that coverage over the **non-self-FK** columns only, so a self-FK column can never count toward pinning the WHERE. For the `email` / campus shapes this changes nothing (the own-identity field covers the PK regardless). In the degenerate shape where a PK column is reachable *only* through the self-FK, coverage correctly fails with `NoUniqueKeyCoverage` ("your identity fields do not pin a key"), which matches the semantic: a self-FK cannot identify the row it lives on.

### Worked example (`email`, single-row UPDATE)

```graphql
input UpdateEmailReplyInput @table(name: "email") {
    id: ID! @nodeId(typeName: "Email")          # own identity → WHERE (mailbox_id, message_no)
    subject: String @field(name: "subject")     # value → SET
    inReplyTo: ID @nodeId(typeName: "Email") @reference(path: [{key: "email_in_reply_to_fk"}])
                                                 # self-FK → SET (mailbox_id, in_reply_to_no)
}
# Mutation
updateEmailReply(in: UpdateEmailReplyInput!): Email @mutation(typeName: UPDATE)
```

- `id` (own `@nodeId`, no `@reference`, R328 own-PK short-circuit) → `keyColumns` `(mailbox_id, message_no)` → WHERE.
- `subject` → `setColumns` `(subject)`.
- `inReplyTo` (self-FK) → `setColumns` `(mailbox_id, in_reply_to_no)`, whole.
- `mailbox_id` is in both `keyColumns` (from `id`) and `setColumns` (from `inReplyTo`) → cross-partition agreement: `requireColumnAgreement("mailbox_id", …, id.value1(), inReplyTo.value1())` before the DML. Agree → update (`mailbox_id` write is a no-op, `in_reply_to_no` repointed); disagree → throw, nothing written.

---

## Implementation

Single-row UPDATE only (bulk is out of scope, see below). Flat file-by-file; the pieces land together (the feature does not compile half-applied).

- **Self-FK marker on the carrier.** Add a `selfReference` boolean to `InputField.ColumnReferenceField` and `InputField.CompositeColumnReferenceField`. It is set where the self-FK is already discriminated: `NodeIdLeafResolver` builds `Resolved.FkTarget.DirectFk` knowing `T.table()` equals the containing table (the self-FK condition, `NodeIdLeafResolver.java:42-44,275-281`); carry that onto `DirectFk` and through `BuildContext.inputFieldFromNodeIdResolved` (`BuildContext.java:2098-2110`) onto the carrier. Every other construction site (the `buildInputNodeIdReference` cross-table shim, the FieldBuilder reference paths, test builders) passes `false`. This is the "fact lives in the model" lift, the walker reads `carrier.selfReference()` rather than re-deriving self-ness from the join path. (Fallback if the marker churn proves disproportionate: detect in the walker via the carrier's terminal `joinPath` target table equal to its own table; the marker is preferred.)
- **`UpdateRowsWalker` routing + coverage.** In `classifyInto` / Stage 6, route a `selfReference()` reference carrier's columns wholly to `setColumns` regardless of key membership; value carriers and cross-table references keep today's partition-by-membership. Compute `MatchedKeys.firstCovered` over the non-self-FK contribution columns. A `selfReference()` carrier on a `multiRow: true` (bulk) UPDATE input rejects with a clear "self-FK `@reference` on a bulk UPDATE is not yet supported" message (new narrow arm or an `UnsupportedInputFieldShape`), see out-of-scope.
- **Narrow `MixedCarrierKeyMembership`.** With self-FK references routed all-SET, the straddle branch no longer fires for them; it survives for a cross-table FK reference that genuinely straddles (kept rejecting, not deleted, per "validator mirrors classifier invariants", the branch is carrier-agnostic and must still reject any straddle the per-column routing does not handle). Update `docs/typed-rejection.adoc`, the `RejectionSeverityCoverageTest` synthesis, and the walker's `compositeReferenceStraddlesKey_…` test to reflect that a self-FK no longer reaches the arm while a cross-FK straddle still does.
- **Cross-partition agreement preamble.** In `TypeFetcherGenerator.buildMutationUpdateFetcher` (single-row arm), before the SET / WHERE emit, emit a preamble for every column present in both `keyColumns` and `setColumns`: decode each side into a self-contained preamble-local record (presence-guarded), then `requireColumnAgreement` reading each side's value at its positional slot within its group (`id` slot for the key side, `inReplyTo` slot for the SET side). Modeled on `emitSetAgreementPreamble` (`TypeFetcherGenerator.java:2698-2771`); reuses `requireColumnAgreement`; emits nothing (byte-identical) when there is no key∩set overlap, so non-self-FK UPDATEs are untouched.

## Tests

The cross-partition agreement is net-new emit, so an execution-tier test is the load-bearing requirement (without it the new scaffold is unpinned): pin the agree / disagree / omitted behaviour end to end, in the `SelfFkNodeIdInsertExecutionTest` mold.

- **Unit (`UpdateRowsWalkerTest`).** A `selfReference()` reference routes all its columns to SET (the shared key column appears in both `keyColumns` and `setColumns`); coverage computed over identity columns (a PK column reachable only via the self-FK → `NoUniqueKeyCoverage`); a self-FK on a bulk input rejects; the existing `compositeReferenceStraddlesKey_…` updated so a self-FK no longer rejects while a cross-FK straddle still produces `MixedCarrierKeyMembership`.
- **Pipeline (`MutationDmlNodeIdClassificationTest`).** `updateEmailReply` classifies with `inReplyTo`'s `(mailbox_id, in_reply_to_no)` in `setColumns`, `id`'s `(mailbox_id, message_no)` in `keyColumns`, and `mailbox_id` present in both; the existing `fkTargetNodeIdRef_arity1_update_admitted` (cross-FK `bazRef` → WHERE) stays green, pinning that the all-SET rule did not leak to cross-table references.
- **Compilation (`graphitron-sakila-example`).** `updateEmailReply` + `UpdateEmailReplyInput` added to the schema; the generated fetcher typechecks against the real jOOQ `Email` at Java 17.
- **Execution (`SelfFkNodeIdUpdateExecutionTest`, new).** On the existing `email` / `mailbox` fixture (no new SQL), the UPDATE mirror of the INSERT test: **agree** (`id`=(5,N), `inReplyTo`=parent in mailbox 5) repoints `in_reply_to_no` and the `mailbox_id` SET is a no-op; **disagree** (`inReplyTo`=parent in mailbox 9) throws via `requireColumnAgreement` and nothing is updated (no silent row-move); **omitted** nullable `inReplyTo` updates `subject` only, no agreement check fires. No generated-body string assertions.

---

## Out of scope (called out, not regressed)

- **Bulk (`multiRow: true`) self-FK UPDATE.** A self-FK's shared column on the bulk SET path lands in the `UPDATE … SET c = v.c FROM (VALUES …)` derived table, which is exactly R342's duplicate-`v`-column dedup territory. R354 rejects it with a clear message and defers it to **R342**, mirroring how R322 carved the bulk SET path off to R342.
- **Cross-table FK reference straddle.** A cross-table FK reference whose lifted columns genuinely straddle the matched key keeps rejecting via the narrowed `MixedCarrierKeyMembership`. Per-column handling for that case is not motivated by any consumer; revisit as a separate item if one emerges.
- **The per-column ordered-writers abstraction lift.** R354 adds one cross-partition agreement preamble (a fifth instantiation of the gather-writers-and-compare scaffold around the already-unified `requireColumnAgreement`). The broader lift into one carrier-agnostic writer model that detect / dedup / agree / reject all read off stays **R342**'s (it flags this explicitly); R354 does not grow it silently but does not perform it either. The execution-tier test above keeps the new scaffold pinned in the interim.

---

## Cross-references

- **R328** (`27d2359`, Done) shipped the self-FK `@nodeId @reference` on the INSERT and read sides and the `email` / `mailbox` fixture. R354 is the UPDATE sibling; it reuses the fixture and the `email_in_reply_to_fk` shape verbatim.
- **R322** (Done) owns `requireColumnAgreement` and the four same-clause agreement sites. R354 reuses the predicate unchanged and extends its reach to the WHERE↔SET boundary, the one overlap R322's per-clause grouping never crossed.
- **R342** (`bulk-update-set-shared-column-dedup`, Backlog) owns the bulk UPDATE SET dedup and the eventual per-column writer-abstraction lift. R354's bulk self-FK case and the scaffold consolidation both land there; the two items share the `requireColumnAgreement` predicate and do not otherwise overlap.
