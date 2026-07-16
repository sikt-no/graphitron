---
id: R314
title: "Dissolve the re-fetch (reentry) leaf fields: emit reentry by switching on the model"
status: In Progress
bucket: architecture
priority: 4
theme: classification-model
depends-on: [coordinate-lowers-to-datafetcher-queryparts]
created: 2026-06-15
last-updated: 2026-07-16
---

# Dissolve the re-fetch (reentry) leaf fields: emit reentry by switching on the model

A vertical slice of the emit re-platforming: the **reentry** (re-fetch) family becomes the first emit
family driven by the R333 model (the coordinate's facts and the named-seam method graph) instead of
leaf identity. Re-specced 2026-07-04 onto R333's vocabulary; the original 2026-06-15 body targeted the
`carrier` / `intent` / `mapping` types R316 deleted and is superseded in full.

## What reentry is, in the model's terms

Today the generator emits reentry by switching on **leaf identity** — `RecordTableField` (the merged
source-gated leaf's record arm after R432), `RecordLookupTableField`, `RecordTableMethodField`,
`ServiceTableField`, the root `QueryServiceTableField` / `MutationServiceTableField`, and projected
`DmlTableField`. The reentry SQL is **uniform** across the family: the keyed re-query
`f(keys, correlation)` — `VALUES(idx, key...) JOIN target ON correlation ORDER BY idx`, scatter —
where `correlation` is PK self-identity (the degenerate case of the FK join; R333 *Two levels of
natural key*). The per-member variation is exactly what the facts own:

- **The source endpoint** owns the key lift: N reads through the ordinary field-level locator facts.
  The lift-arm selection **reads the type-level source-object fact** — the same fact the accessor
  read side already forks on (jOOQ record → column projection; Java object → member read, with
  `@sourceRow` as authored provenance on that arm) — and reuses the accessor locator per key column.
  The "two arms" are the source-object shape, not a new sealed switch the key lift owns: a second
  field-granular gate at the lift site would be the same predicate evaluated by a second consumer.
  Post-R431 this lives on the decomposed source-object / locator facts, not on `SourceKey.Reader`.
  With R461 (`unify-sdl-field-accessor-resolution`, Done 2026-07-13), "the accessor locator" names
  one seam, `ClassAccessorResolver`'s shared candidate model, not four divergent resolutions —
  which is what makes "reuses the accessor locator per key column" a single-consumer claim rather
  than a fifth re-derivation of the name rules.
  There is no liveness axis; "same keys, same rows" is the whole contract.
- **The operation** owns the per-member payload: `Fetch` a plain re-projection, `Lookup` the
  positional correspondence (`LookupMapping`), `ServiceCall` the service lift, the DML arms their
  projected payload.
- **The target** owns the projection: the re-projected `@table` (`$fields`), pagination on
  `Paginate`.

So `emit(reentry) = f(source facts, operation, target)` with no `instanceof` on leaf classes. One
line to hold: `correlation` as a uniform column-pair set with PK self-identity as the degenerate arm
is clean **only if no emit site forks on "is this self-identity"**; if an emit site needs that
predicate, it belongs in the model as a fact, not recomputed at the emit site.

**Arrival is a fact this slice reads for lift width, not for strategy** (settled 2026-07-13, after
R463 landed). Since R463 the source fact carries the true arrival arm (`Root` / `OnlyChild` /
`Child`), and `Source`'s javadoc names the wrapper as the emit-strategy dispatch: `Child` batches
through a DataLoader, `Root` / `OnlyChild` run SQL directly. The two counts are distinct: arrival
bounds DataLoader invocations, not lift width — a `Root` service reentry still lifts N keys from its
N produced rows, and the keyed re-query degenerates cleanly at one key. This slice stays
**arrival-uniform**: it switches the reentry emit on the source-shape / operation / target facts and
`requiresReFetch`, and deliberately does *not* consume the `OnlyChild` / `Child` arm as a strategy
fork — an `OnlyChild`-classified reentry field keeps its one-element batch. The direct-SQL
`OnlyChild` emit is decided out, not deferred as an open verdict: it changes query shape and SELECT
counts, so it definitionally cannot land under this item's execution-tier-equivalence acceptance.
**R471** (`direct-sql-onlychild-reentry-emit`) owns it, together with the honesty-clause enforcer the
`Source.OnlyChild` javadoc requires; that javadoc's forward pointer (previously "rides the R431 →
R432 → R314 chain") now names R471. After this slice, the arrival arm is a landed-but-unconsumed
fact at the reentry emit site, and R471 is its recorded consumer — "emit by switching on the model"
here means the facts listed above, not the arrival wrapper's documented dispatch.

## Goals

1. **Emit the keyed re-query off the model.** Re-platform the reentry emit in `TypeFetcherGenerator`
   / `SplitRowsMethodEmitter` to compose the one primitive from the facts above instead of dispatching
   per re-fetch leaf.
2. **Dissolve the re-fetch leaves.** Reduce `RecordLookupTableField`, `RecordTableMethodField`,
   `ServiceTableField`, and the reentry arms of the root service and projected DML fields to thin
   records or remove them, once their distinguishing data lives on the facts.
3. **Retire `dispatchPerformsReFetch` and its mirror test.** The generator consults
   `OutputField.requiresReFetch()` (already axis-derived since R305/R316) directly, so the derivation
   and the emit cannot drift by construction — "if two consumers evaluate the same predicate over a
   model field, the branch belongs in the model."
4. **First seam-named family, and the bidirectional oracle that asserts it.** The reentry query unit
   gets regime-1 naming (model-carried method names, thread J). **This slice builds the command/name
   registry and thread I's bidirectional closure oracle** (every emitted method is exactly one
   command's output; every callee name resolves to a committed command) and populates it for the
   reentry family — the bidirectional check is this item's deliverable, not a pre-existing gate. The
   level-1 characterization oracle over the whole emit ships earlier under R333's Ready deliverable
   and is the harness this slice runs against throughout.

## Scope

The reentry emit family only — not all of generation. Other emit families (inline projection arms,
plain `@splitQuery` beyond the R432-merged leaf, polymorphic, connections, DML internals) stay
leaf-dispatched until their own slices. No input-side work; no `TypeFetcherGenerator` decomposition
beyond what the reentry path forces (R7 stays separate). The seam-worklist verdict this slice must
state: row 15 (channel catch / early-return arms), since the service reentry path crosses the error
channel; other open promote-or-inline verdicts (rows 12–14, 16) stay per-slice calls for later
families. The `OnlyChild` strategy question is *not* such a verdict: it is settled above
(arrival-uniform; direct SQL is R471's), so the implementer inherits a decision, not a fork.

## Acceptance

**Execution-tier equivalence, not byte-for-byte output equality** (settled 2026-07-04): the goal is
gradual improvement toward R333/R222, so the slice may normalize generated-code shape as it goes. The
gates are the R305 reentry execution tier (`SingleRecordPayloadDmlTest`,
`SingleRecordTableFieldServiceProducerExecutionTest`, the LocalContext error path) — same rows, same
order, error paths intact — plus the
`@classified` corpus classifying unchanged, plus the level-1 closure oracle staying green across the
re-platforming, plus the goal-4 deliverable: the bidirectional closure oracle exists and passes for
the reentry family.

## Implementation plan (In Progress, 2026-07-16)

Slices, each full-reactor green and shipped to trunk. The plan folds a principles-architect
consultation (2026-07-16) whose three central findings share one shape: invariants the first sketch
stated in prose are carried instead as derived facts off the model.

**The site-level fact.** Recon confirmed the root-service divergence: `QueryServiceTableField` /
`MutationServiceTableField` have `requiresReFetch() == true` yet emit a direct passthrough with no
re-query at their own site (the re-projection is realized by the downstream child fetchers'
`$fields`). So `requiresReFetch()` is a value-level fact ("this field's value is re-projected
somewhere") and the emit needs a distinct site-level one ("this coordinate emits the keyed re-query
here") — R333's operation-set membership, in minimal form. Slice 1 lands it as a derived model
predicate next to `requiresReFetch()` (absent exactly on the root service arm), and every later
consumer (emit dispatch, registry family boundary, the slice-5 validate guard) reads it rather than
recomputing `requiresReFetch() && !rootService` per site.

1. **Site-level reentry fact + command registry + bidirectional oracle.** The derived fact above;
   a main-source command/name registry surfaced on the generation result alongside
   `emittedUnits()`; the bidirectional oracle (every covered emitted method is exactly one
   committed command's output; every callee edge into covered methods resolves to a committed
   command). Two hard conditions from the consultation: the registry is the **name authority** —
   the reentry family's current name derivations (`rowsMethodName`, the service-lift `load<X>`,
   the DML follow-up sites) route *through* command minting so the emitter reads the name off the
   command (regime-1), never registers a parallel description beside its own formula (the R268
   drift); and the covered-family boundary **derives from the site-level fact**, never a
   hand-attached tag. Green before any re-platforming; slices 2-4 run under it.
2. **Child record-sourced reentry onto the model-composed unit.** The `Batched*` Record arms and
   `RecordTableMethodField` compose the keyed re-query from `(sourceKey/lift, operation, target)`;
   `RecordTableMethodField` dissolves (or thins) once its distinguishing data lives on the facts;
   the `SqlRecordTableMethod` permit retires if residue-free.
3. **Service reentry.** `ServiceTableField`'s lift onto the same unit. Row-15 verdict (stated):
   the channel catch / early-return arms **stay folded into the Fetcher** — no independent seam —
   with the load-bearing premise ("reentry service fields are single-channel / the arms are
   strategy-invariant across the family") pinned by an enforcer (validate-time rejection or
   pipeline-tier assertion), not left as prose. Root service leaves thin to records whose absent
   site-level fact carries the passthrough distinction; where their reentry is realized
   (downstream `$fields`) gets documented, not re-implemented.
4. **DML projected reentry.** `emitProjected` / `emitDiscriminated`'s follow-up SELECT composes
   through the same unit: **one rendering** — the VALUES-join with PK self-identity as the
   degenerate correlation (per R333's re-query unification resolution) — with scatter gated on the
   arrival/wrapper axis (absent for the single-anchor DML case), never a PK-IN "second rendering"
   forked on an emit-site self-identity predicate. Transaction boundary (follow-up outside the
   write transaction) stays pinned by `SingleRecordPayloadDmlTest`. If normalization turns out to
   change observable query shape or SELECT counts beyond execution-tier equivalence, PK-IN stays as
   recorded residue with a named successor Backlog item (the `OnlyChild` precedent), not as a
   permanent second rendering.
5. **Retire `dispatchPerformsReFetch`.** The generator consults the model facts directly; the
   validator mirror error retires **in the same slice** as a replacement validate-time guard
   sourced from the slice-1 site-level fact (an unsupported reentry classification still fails in
   `ValidateMojo`, not at runtime); `ReFetchDerivationTest`'s derivation assertions stay,
   mirror-agreement assertions repoint.
6. **Docs sweep + spec shipped-notes → In Review.**

## Lineage

Follows **R305** (dissolved `SingleRecordTableField`, made `requiresReFetch` axis-derived, kept the
emit leaf-dispatched), **R316** (the `(source, operation, target)` pivot; its changelog entry pins
"Collapses to one carrier under R314" on `Operation.Call`), and **R333** (the model this slice
consumes; its Relationships section records the decided sequence). **R463** (Done 2026-07-13)
populated the `Source.OnlyChild` arrival arm this slice reads but does not strategy-fork on (see
above; the direct-SQL emit is R471's), and **R461** (Done 2026-07-13) unified accessor resolution
behind the one candidate model the key lift reuses. Run-up: R431
(`decompose-sourcekey`) then R432 (`collapse-split-and-record-table-leaves`, the beachhead), then this
item. The original body's insight — reentry SQL is uniform, variation belongs to the axes — survives
verbatim; only the vocabulary and the slot destinations moved.
