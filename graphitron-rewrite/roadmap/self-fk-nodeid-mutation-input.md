---
id: R328
title: "Self-FK @nodeId reference on Graphitron-owned DML mutation inputs"
status: Backlog
bucket: feature
priority: 4
theme: nodeid
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# Self-FK @nodeId reference on Graphitron-owned DML mutation inputs

A same-table `@nodeId` on a `@mutation` INSERT/UPDATE input that is semantically a
self-referencing FK is rejected today. `NodeIdLeafResolver.resolve` short-circuits to
`Resolved.SameTable` the moment the `@nodeId` target table equals the input's own table
(line 269), before `resolveFkJoinPath` runs. A composite key then lands on
`InputField.CompositeColumnField` and trips the R130 `CompositeColumnField x INSERT`
carve-out; and even if that carve-out were lifted, the `SameTable` arm writes the
decoded id into the row's **own** PK columns rather than the FK columns, which is
semantically wrong. This is the "separate design item" R315 defers self-reference to
(R315 "Out of scope": *"Supporting it needs a new disambiguator ... a separate design
item"*).

## Motivating consumer

`utdanningsregisteret-graphql-spec` `opprettCampus`:

```graphql
input OpprettCampusInput @table(name: "CAMPUS") {
  larestedId: ID! @nodeId(typeName: "Larested")    # cross-table FK, admitted (R189)
  kode: String! @field(name: "CAMPUSKODE")
  eierCampusId: ID @nodeId(typeName: "URegCampus")  # SELF-FK to parent campus, REJECTED
}
```

`URegCampus` is `@node(keyColumns: ["organisasjonskode","campuskode"]) @table(name: "CAMPUS")`;
the self-FK `CAMPUS__CAMPUS_EIER_CAMPUS_FK` is `(organisasjonskode, campuskode_eier) ->
(organisasjonskode, campuskode)`. The read side already disambiguates the same self-FK
with `eierCampus: URegCampus @reference(path: [{key: "CAMPUS__CAMPUS_EIER_CAMPUS_FK"}])`;
the write side has no honored equivalent.

The blocked upgrade should not wait on this item; the consumer unblocks now by routing
`opprettCampus` through a `@service` (its neighbors `opprettOrganisasjon` /
`opprettUtdanningsspesifikasjon` already do). This item lets the schema later drop the
`@service` and express the relationship as `eierCampusId: ID @nodeId(typeName: "URegCampus")
@reference(path: [{key: "CAMPUS__CAMPUS_EIER_CAMPUS_FK"}])`, symmetric with the read side.

## Design sketch (resolve at Spec)

1. **Cross-path disambiguator.** An explicit `@reference(path: [{key: ...}])` on a
   same-table `@nodeId` means "self-FK reference: write the FK source columns via
   `liftedSourceColumns`", not "own-PK identity". This is one classification fact;
   per Generation-thinking it should be resolved once (in the shared FK-pairing core
   R315's D3 extracts) and consumed by both `NodeIdLeafResolver` (the `@mutation` /
   argument path) and `InputBeanResolver` (R315's `@service`-record path), so the two
   resolvers cannot drift on what `@reference` on a same-table `@nodeId` means. When
   this item lands it **replaces R315's same-table-`@reference` reject arm with admit**
   on both paths (R315 rejects it today precisely as the deferred-to-here case).

2. **Classifier.** Gate the line-269 same-table short-circuit on the absence of
   `@reference`; with `@reference` present, fall through to `resolveFkJoinPath`, whose
   existing `selfRefFkOnSource=true` arm resolves the self-FK and produces the R189
   `ColumnReferenceField` / `CompositeColumnReferenceField` carriers. No new sealed
   variant. (Prototype done: the one-line gate; 110 NodeId classifier tests stay green
   because it only fires when `@reference` is present, leaving the common same-table
   lookupKey case untouched.)

3. **Emit: structural dedup only.** Self-FK source columns overlap the row's own PK
   (CAMPUS `organisasjonskode` is written by both `larestedId` and the `eierCampusId`
   decode), so `buildInsertColumnList` / `buildPerCellValueList` emit the column twice
   and Postgres rejects the INSERT ("column specified more than once"). Skip a column
   already contributed by an earlier leaf; the PK / earlier field owns it. This is a
   pure SQL-validity concern and stays a mechanical walk (no classify-time narrowing of
   `liftedSourceColumns`, which would be a silent last-write-wins encoded in the
   classifier). The general self-FK case with no PK overlap needs no dedup and behaves
   as a plain `DirectFk`.

4. **Shared-column agreement guard defers to R322.** Whether the two writers of the
   shared column agree is data-dependent (values arrive off the wire) and is R322's
   chartered, cross-path home. This item ships **last-write-wins** on the shared column
   in the interim, exactly as R315 does for its analogous overlap edge. Do **not** bolt
   an agreement check into the INSERT emitters here.

## Reproduction surface (proposed, prototyped out-of-tree)

A neutral mirror of the CAMPUS shape, validated in a throwaway prototype during this
item's discovery (not committed):

- A `nodeidfixture` `email` / `mailbox` pair (`init.sql`): composite-PK `email`
  (mailbox_id, message_no) with self-FK `email_in_reply_to_fk`
  `(mailbox_id, in_reply_to_no) -> (mailbox_id, message_no)` plus a cross-table
  `email.mailbox_id -> mailbox` FK; node metadata in `NodeIdFixtureGenerator.METADATA`
  (remember to bump `jooq.codegen.schema.version`).
- `MutationDmlNodeIdClassificationTest` cases: self-FK rejected today; with the line-269
  gate, admits as a `CompositeColumnReferenceField` over `liftedSourceColumns`
  `(mailbox_id, in_reply_to_no)` (surfacing the shared `mailbox_id` overlap); plus a
  cross-table contrast that already admits.

The prototype confirmed: the classifier change is a one-line gate (110 NodeId tests stay
green), and the emit break is exactly the duplicate shared column in
`buildInsertColumnList` / `buildPerCellValueList` (the dedup in design point 3).

## Coordinates with

- **R315** (Ready, `fk-reference-nodeid-service-record-input.md`): the sibling
  `@service`-record path; currently rejects this SDL shape as the deferred self-reference.
- **R322** (Backlog, `nodeid-shared-column-agreement.md`): owns the shared-column
  runtime agreement guard across both paths.
- **R130** (INSERT carve-out), **R189** (FK-target reference carriers on INSERT/UPDATE/DELETE).

## Open question for Spec

SDL surface: `@reference(path: [{key: ...}])` (read/write symmetric, `parsePath` already
handles it) versus R315's mooted `@reference(key:)` shorthand. Pick one for both paths.
