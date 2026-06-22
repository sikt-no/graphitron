---
id: R354
title: "Self-FK @nodeId on UPDATE: partition lifted columns by PK/UK membership instead of rejecting the WHERE/SET straddle"
status: Backlog
bucket: feature
priority: 5
theme: nodeid
depends-on: []
created: 2026-06-22
last-updated: 2026-06-22
---

# Self-FK @nodeId on UPDATE: partition lifted columns by PK/UK membership instead of rejecting the WHERE/SET straddle

R328 lets an explicit `@reference` on a same-table `@nodeId` mean "follow this self-FK and write its child columns" on a Graphitron-owned DML input. It lands on `@mutation(typeName: INSERT)`, where R322's per-column dedup reconciles a self-FK child column that coincides with a PK column. On `@mutation(typeName: UPDATE)` the same authoring is rejected: when the self-FK's child columns straddle the row's identity key — some columns are PK/UK members (which form the UPDATE's WHERE), some are not (which form the SET) — the validator fails with *"input field '<f>' lifts columns that straddle the matched key: {…} are in the key but {…} are not. A single input field cannot span the WHERE and SET partitions."* The straddle check partitions at **input-field granularity** (a whole `@nodeId` field must sit entirely in WHERE or entirely in SET), so a self-FK whose child columns overlap the PK has no expressible UPDATE form. Without the `@reference`, the bare same-table `@nodeId` silently degrades to the own-PK short-circuit — semantically wrong, since the field wants a *different* row's id (the parent), not a restatement of the row's own identity — and survives validation only because R322 defers the "two ids on the same columns" conflict to a runtime agreement check. So today UPDATE has no correct authoring for a self-FK that shares a key column.

Motivating consumer: `utdanningsregisteret-graphql-spec` `endreCampus` / `EndreCampusInput.eierCampusId` (`@nodeId(typeName: "URegCampus") @reference(path: [{key: "CAMPUS__CAMPUS_EIER_CAMPUS_FK"}])`). The campus PK is `(organisasjonskode, campuskode)`; the self-FK `CAMPUS__CAMPUS_EIER_CAMPUS_FK` child columns are `(organisasjonskode, campuskode_eier)`. `organisasjonskode` is in the PK (a campus's parent is always in the same organisasjon, so the FK shares it), `campuskode_eier` is not. Adding the `@reference` (correct under R328) trips the straddle check; the INSERT form (`opprettCampus`) accepts the same `@reference` and works.

## Design direction (from filing)

Partition the lifted columns by **per-column PK/UK membership**, not by which `@nodeId` input field they belong to. For a self-FK `@nodeId` on UPDATE, the child columns that *are* PK/UK members are pinned by the row identity (they belong to / agree with the WHERE key and are already fixed there — `organisasjonskode` cannot change and needs no SET), and the remaining child columns become the SET targets (`campuskode_eier`). Retire the field-granularity "cannot span WHERE and SET" rejection in favour of routing each lifted column to WHERE or SET by whether it is a key column, with the shared key column reconciled against the identity the way R322 reconciles shared columns on INSERT. Out of scope until Spec: the exact reconciliation semantics when a shared key column's decoded value disagrees with the identity field's value (reuse R322's agreement check vs. reject), and whether a UK (non-PK) match changes the WHERE key selection.

## Relations

Direct follow-on to **R328** (self-FK `@nodeId` on DML inputs — shipped the INSERT path). Leans on **R322** (value-agreement for `@nodeId` decodes onto shared columns).
