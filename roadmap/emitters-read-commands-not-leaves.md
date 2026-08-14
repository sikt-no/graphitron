---
id: R682
title: "Emitters read commands, not leaves: close the fact-to-plan-to-emit seam"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Emitters read commands, not leaves: close the fact-to-plan-to-emit seam

The intended architecture is one sentence. Capture writes facts; the classification walk's sealed
leaves dissolve into those facts rather than growing; planners read facts and produce commands; and
emitters render commands. Each tier reads only the tier below it, so an emitter never reaches past
its command into the thing that produced it.

Half of that is built and enforced. The other half is built and *not* enforced, and the gap is not
evenly spread: it is concentrated in one package.

## Where the line actually falls today

`no.sikt.graphitron.render` already lives under the rule. It contains no dispatch on a
classification leaf and imports none of the leaf hierarchies; `PackageImportDirectionTest` pins that
structurally, restricting the package to commands plus a named dial of pure-data model refs. A
renderer there cannot reach a leaf even by accident.

`no.sikt.graphitron.rewrite.generators` is the same job under none of the rules. It is outside that
guard, and it is where the un-migrated emitters live: `TypeFetcherGenerator` (around 6,000 lines)
and `FetcherEmitter` between them carry the leaf dispatch, and `TypeFetcherGenerator` still
enumerates its coverage as a set of leaf classes (`IMPLEMENTED_LEAVES`, 36 entries) rather than as
rows of a command relation. Nothing prevents an emitter there from reading whatever it likes off a
leaf, which is not a hypothetical: it is how a recent design landed on the wrong carrier, because
"join the fact onto the command row" and "read it off the leaf the emitter already holds" are both
reachable and only one is right.

`no.sikt.graphitron.plan` reads leaves on purpose today. That is correct for now (a planner has to
read *something*, and the facts it would rather read are still being modelled) and wrong at the end
state, where planners read facts.

## The instrument already exists and already declares the target

`CommandSeamRatchetTest` was installed by the `facts-and-commands` programme and measures exactly
this seam. Its own javadoc states the terminal condition in as many words: the generators-side
counts "ratchet down to zero", and the plan-side count is "expected to rise while producers are fed
by leaf dispatch and to ratchet back to zero when the fact-visitor engine re-sources them". Live
pins at filing:

[cols="3,1,4"]
|===
| Pin | Value | Meaning

| `MODEL_TAKING_ENTRY_POINTS`
| 18
| entry points in `generators/` still taking the whole schema

| `GENERATOR_LEAF_INSTANCEOF_SITES`
| 69
| `instanceof` sites in `generators/` naming a leaf of the seven hierarchies

| `GENERATOR_LEAF_CASE_PATTERNS`
| 60
| the same for `case` patterns

| `PLAN_LEAF_REFERENCES`
| 125
| leaf references in `plan/`, the one pin that legitimately rises before it falls
|===

So this item proposes no new architecture. The architecture is decided, the triangle is built, the
guard exists for one package and the counters exist for the rest. What is missing is an item that
owns driving the three generators-side pins to zero and then extending the structural guard over the
package they measure, so the rule stops being a ratchet and becomes the same build gate `render`
already lives under.

The reason to own it explicitly rather than let it happen slice by slice: a ratchet with no owner is
a flat line. Each feature item that touches an emitter pays a little of this cost and none of them
is responsible for finishing, which is how the counts sat where they are. A stalled relocation is
precisely what the tertiary counter's comment says the instrument exists to make visible.

## What a Spec would have to settle

* **Slice order, and what a slice is.** The families are not equal: some already have a command
  relation and need only the emitter cutover, others need the relation minted first. The launcher
  family is done, the routine-write family is scoped as a worked example (below), and the rest need
  a census before an order can be argued.
* **Whether `plan/` reading leaves is in scope or is a separate endgame.** The user-facing sentence
  covers both tiers, but the two halves have different blockers: the emitter half is blocked on
  nothing, and the planner half is blocked on the facts existing to read. Splitting them may be
  right; if so this item is the emitter half and the planner half is filed against the fact model.
* **What happens to `TypeFetcherGenerator`.** At zero it has no leaf dispatch left, which is a
  different file from the one `roadmap/decompose-typefetchergenerator.md` (R7) proposed decomposing.
* **The end-state guard.** Extending `PackageImportDirectionTest` over the emitters' package is the
  obvious closer, but only once the package is empty of leaf readers; the sequencing between the
  last slice and the guard needs stating so the guard does not land as a wall of suppressions.
* **Whether the ratchet retires at zero.** A pin at zero that can only be raised by a rule violation
  is arguably a guard already, and keeping both would be two mechanisms for one invariant.

## Relationship to other items

* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333) owns the model: the facts that
  replace the leaves, and the method graph the emit lowers onto. This item is the **consumption**
  side and must not redesign facts. The two meet at the plan tier: R333 decides what a planner reads,
  this item decides that a planner is the only thing that reads it.
* The `facts-and-commands` programme (Done, see `roadmap/changelog.md`) built the
  `command` / `plan` / `render` triangle, `EmitPlan`, the command relations and these ratchets. This
  item is that programme's completion condition, not a re-run of it. Its slice logs are the
  reference for how a family migrates and what holding output identical costs.
* `roadmap/nodeid-key-projection-on-routine-params.md` (R668) carries the routine-write family's
  migration as a stage, because a feature there needed a carrier and the leaf was the wrong one.
  That stage is the worked example this item generalises from: a command relation minted in `plan`
  from two leaves, two emitters moved to `render`, the borrow dial extended, output held identical.
  If R668 lands first, this item inherits a proven recipe and one fewer family; if this item is
  picked up first, R668's stage 4 should be lifted onto it.
* `roadmap/decompose-typefetchergenerator.md` (R7) asks how to break up `TypeFetcherGenerator` and
  offers decomposing along the field taxonomy as its leading option. That option is superseded: the
  file does not get decomposed along the leaves, it empties into `render` as the families migrate.
  R7 should be re-scoped or discarded when this item reaches Spec, and it should not be picked up
  independently in the meantime.
