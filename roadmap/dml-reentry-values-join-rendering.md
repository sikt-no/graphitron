---
id: R489
title: "Normalize the DML reentry correlation onto the VALUES-join primitive"
status: Spec
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-16
last-updated: 2026-07-21
---

# Normalize the DML reentry correlation onto the VALUES-join primitive

## In one paragraph

R314 named the DML reentry unit (the `rows<Name>` companion holding the projected / discriminated
mutation's follow-up SELECT, minted through the method-command registry) but kept its correlation
rendering as recorded residue: the companion keys the SELECT with a keys-IN condition, while R333's
re-query unification resolution names one primitive for every keyed re-query, a `VALUES(idx, key...)`
derived table joined to the target over a correlation, with PK self-identity as the degenerate case.
The two renderings express the same correlation; carrying both is a same-primitive-two-spellings
residue. This item normalizes the companion onto the primitive and, in doing so, converts two pieces
of currently *undefined* bulk behavior into a deliberate, execution-tested contract: payload rows
align one-to-one, in order, with the rows the write reported through RETURNING.

## What is

- `emitProjected` and `emitDiscriminated` (`TypeFetcherGenerator`; `:4991` / `:5187` at the time of
  writing) render the companion's follow-up SELECT as `select ... from T where <PK-IN>` through
  `buildPkKeysCondition`, a thin wrapper over `buildKeysInCondition`. The bulk arm ends in a bare
  `.fetch()`: no ORDER BY and no Java-side reorder.
- Two bulk-arm behaviors are therefore undefined today: payload row order is whatever the database
  returns, and duplicate writes to the same row collapse to one payload row (IN dedupes), so payload
  cardinality can diverge from the write count. Neither is documented or fixture-pinned anywhere.
- The batched rows methods already render the primitive: `ValuesJoinRowBuilder` (the shared cells /
  row-type / alias core, carrying jOOQ's typed `Row22` cap) fed by `SplitRowsMethodEmitter`, with the
  correlation carried as a classified `ParentCorrelation` fact; its `OnLiftedSlots` arm is documented
  as the PK-self-identity degenerate case of the FK pairing.
- The DML classification carries no correlation fact: the emitters recompute the key column set from
  `TableRef.primaryKeyColumns()` at emit time, at two sites.
- `emitKeysTransaction` runs the write with a PK-only RETURNING and yields `keys` as
  `Result<RecordN<...>>` (bulk) or `RecordN<...>` (single); the companion signature is
  `rows<Name>(keys, env)`.
- The routine-write step-2 re-read (`TypeFetcherGenerator` `:1846` at the time of writing) is
  `buildKeysInCondition`'s only other caller and sits outside the reentry family
  (`Operation.RoutineWrite` is outside `requiresReFetch`'s produced-record set).

## Design

Three decisions, in dependency order (principles-architect consulted 2026-07-21).

**The correlation becomes a carried model fact.** The classifier attaches the PK-self-identity
correlation (the `ParentCorrelation.OnLiftedSlots` shape over the bound table's primary key) to the
DML reentry classification at parse time, on whichever carrier the fact naturally keys
(`MutationField.DmlTableField` or the reentry-side fact R314 introduced). `emitProjected` /
`emitDiscriminated` read the carried fact instead of recomputing the PK set at emit. This is the
decide-once rule: the batched re-fetch and the DML reentry consume the same classified correlation,
and R333's "one primitive `f(keys, correlation)`" is realized as routing a model-carried correlation
through the shared renderer, not as recomputing the correlation to feed it.

**The shared seam is the correlation renderer, not the scatter envelope.** What the companion shares
with the batched rows methods is `ValuesJoinRowBuilder` plus a join-predicate helper keyed by the
carried correlation. The envelope stays per-consumer: the companion is RETURNING-keyed, returns the
payload directly, orders by `idx`, and never scatters (scatter is gated on the arrival axis; the
single-anchor DML case never fires it), while the DataLoader envelope keeps `keys.get(i)` and
`scatterByIdx`. Do not thread the DML shapes through `SplitRowsMethodEmitter`'s cardinality entry
points as flag parameters; follow that emitter's own precedent of sibling cardinality methods
sharing a prelude.

**Single-row arms share the seam, not the SQL shape.** At row-count 1 the correlation-resolution
seam renders the legible degenerate, plain PK equality, with no VALUES table and no ORDER BY; the
emitted single-arm output stays behavior-identical (generated code is a consumer artifact). The
honest claim is one correlation seam at two cardinalities, not VALUES-of-one.

## The contract

Bulk projected / discriminated mutation payloads align one-to-one, in order, with the rows the write
reported through RETURNING: the companion orders by `idx`, where `idx` indexes the RETURNING result,
and emits one payload row per written row (row-per-write, not row-per-distinct-key). The
execution-tier fixtures are the enforcer; the user-manual sentence describes what they pin.

Docs draft (first-client check), for the mutation reference page, mirroring the `@lookupKey`
precedent ("returns a list of results in the same order"): "The payload list contains one entry per
written row, in the order the rows were written."

## Implementation

- Classifier: attach the PK-self-identity `ParentCorrelation` to the DML reentry classification;
  the emit reads it, never derives it.
- `TypeFetcherGenerator.emitProjected` / `emitDiscriminated`: build the companion body through the
  shared correlation renderer; the bulk arm gains `ORDER BY idx`; the single arm renders the
  degenerate equality.
- `buildPkKeysCondition` is deleted: its only two callers are the arms above, so it is dead
  post-migration. `buildKeysInCondition` narrows to its single routine-write caller; pin that
  single-caller status (visibility, or a comment naming the routine-write re-read as the sole
  sanctioned caller) so the keys-IN spelling cannot silently regrow callers before
  `Operation.RoutineWrite` joins the reentry family.
- Validator: the existing validate-time rejection mirroring `ValuesJoinRowBuilder`'s `Row22` cap
  (the `GraphitronSchemaValidator` "exceeds jOOQ's typed Row22 cap" rule) extends to the DML reentry
  key, so a PK whose arity plus the `idx` slot exceeds the cap rejects at validate time, never as an
  emit-time exception.
- The `rows<Name>` unit's registry identity does not change; only its body does. The method-command
  registry and `ReentryCommandClosureTest` stay untouched (the registry is a census of unit
  identity, not of body content).

## Tests and acceptance

Primary acceptance is the behavioral contract at the execution tier, because the fixtures are the
contract's enforcer:

- a bulk projected mutation fixture asserting payload order equals RETURNING (input) order, on an
  input deliberately ordered against the table's natural order;
- a duplicate-write fixture (bulk update targeting the same row twice) asserting two payload rows;
- a discriminated bulk fixture asserting the same order contract through the re-projection path;
- single-row arms behavior-identical (plain equality, no order clause), byte-identical where the
  rendering permits.

Explicitly *not* byte-identical and *not* plain execution-tier equivalence on the bulk arms: the
order and cardinality change is the point, and it needs its own fixtures rather than equivalence
against the undefined baseline. Full reactor green under `-Plocal-db`; sakila corpus green.

## Out of scope

- The routine-write step-2 re-read stays keys-IN until `Operation.RoutineWrite` joins the reentry
  family; if that framing changes, its re-read joins this unit and the surviving keys-IN helper
  retires with it.
- Scatter on the DML companion (arrival-gated; never fires on the single-anchor case).
- Any RETURNING semantics change; `emitKeysTransaction` is untouched.

## Roadmap ripple

On Done: update the R314 shipped-note in R333 (`coordinate-lowers-to-datafetcher-queryparts.md`),
whose residue sentence names this item as the owner of the keys-IN vs VALUES-join normalization,
and record the landing in `changelog.md` per the Done convention.
