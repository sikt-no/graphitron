---
id: R682
title: "Planners read facts, emitters read commands: close the seam on both tiers"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Planners read facts, emitters read commands: close the seam on both tiers

The intended architecture is one sentence. Capture writes facts; the classification walk's sealed
leaves dissolve into those facts rather than growing; planners read facts and produce commands; and
emitters render commands. Each tier reads only the tier below it, so a planner never reaches past
the facts into the walk that produced them, and an emitter never reaches past its command into the
thing that produced it.

This item owns getting there, on both tiers. It is a programme rather than a single change: the
planner half is already specced in detail as its own item (below), and the emitter half is
unstarted. What has not existed until now is one owner for the end state.

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

`no.sikt.graphitron.plan` reads leaves, not facts. `EmitPlan.produce` takes a `GraphitronSchema` and
dispatches on sealed variants to build the six command relations, so no generated file has ever been
produced from the store. That is the planner half, and it is the larger of the two.

## The instrument already exists and already declares the target

`CommandSeamRatchetTest` was installed by the `facts-and-commands` programme and measures this seam
on both tiers. Its own javadoc states the terminal condition in as many words: the generators-side
counts "ratchet down to zero", and the plan-side count is "expected to rise while producers are fed
by leaf dispatch and to ratchet back to zero when the fact-visitor engine re-sources them". Live
pins at filing:

[cols="3,1,4"]
|===
| Pin | Value | Tier

| `MODEL_TAKING_ENTRY_POINTS`
| 18
| emitters: entry points in `generators/` still taking the whole schema

| `GENERATOR_LEAF_INSTANCEOF_SITES`
| 69
| emitters: `instanceof` sites in `generators/` naming a leaf of the seven hierarchies

| `GENERATOR_LEAF_CASE_PATTERNS`
| 60
| emitters: the same for `case` patterns

| `PLAN_LEAF_REFERENCES`
| 125
| planners: leaf references in `plan/`, the pin that legitimately rose before it falls
|===

All four go to zero. That is the whole item, stated numerically.

So it proposes no new architecture. The architecture is decided, the triangle is built, the guard
exists for one package and the counters exist for the rest. What is missing is an owner for driving
all four pins to zero and then extending the structural guard over the packages they measure, so the
rule stops being a ratchet and becomes the same build gate `render` already lives under.

The reason to own it explicitly rather than let it happen slice by slice: a ratchet with no owner is
a flat line. Each feature item that touches an emitter or a producer pays a little of this cost and
none of them is responsible for finishing, which is how the counts sat where they are. A stalled
relocation is precisely what the tertiary counter's comment says the instrument exists to make
visible.

## The facts to plan against are available

The planner half was previously sequenced behind the fact population it needed. That blocker has
largely cleared, and the distinction matters because it decides what remains:

* **The expensive population is there.** The per-coordinate classification stratum
  (`intent_authored_field_claim`, `intent_resolved_field_claim`, `intent_authored_type_claim`, and
  the demand and exemption rules beside them) is captured and derived, and the language server
  already reads it arm by arm. That was the population the planner half was waiting on and the
  reason it wanted a worked example first. The example shipped.
* **What remains is plumbing, not modelling.** Four relation-shaped folds have no home in the store
  yet: operation members, connection synthesis, tenant bindings, and delivery. None needs a new
  rule. Each was already built as a relation in the model and needs a capture-cadence writer and a
  view, which is the cheapest kind of work in this programme.
* **One live dependency remains** on the delivery verdict's own item, which derives that fold and
  stops deliberately short of flipping consumers.

The practical consequence: the two halves are no longer blocked on different things, which is what
justified keeping them apart. Both are now sequencing problems rather than modelling problems, which
is why one item owns them.

## How it decomposes

* **Planner half.** `roadmap/emit-plan-reads-the-store.md` is the specced slice and keeps its own
  body, dependency and review history. Its success criterion is exact: `EmitPlan.produce` takes a
  `StoreHandle` and no `GraphitronSchema`, with the six command relations converting in dependency
  order. Nothing here supersedes it; this item carries it as its planner half and inherits its
  sequencing.
* **Emitter half.** Unstarted, and the smaller surface. Families migrate one at a time: a command
  relation minted in `plan` from the leaves, the emitters moved to `render` reading only that row,
  the borrow dial extended, output held byte-identical. The routine-write family is already scoped
  as a worked example (below).
* **The closer.** Extending `PackageImportDirectionTest` over both packages once they are empty of
  leaf readers, and deciding whether the ratchet pins retire at zero or stay as a second mechanism.

## What a Spec would have to settle

* **Whether the two halves interleave or serialise.** They touch different packages and could run
  concurrently, but the emitter half's cutovers are easier to verify against a plan that is not
  simultaneously changing its own inputs. This is the main sequencing question.
* **Slice order within the emitter half**, which needs a per-family census before it can be argued:
  some families already have a command relation and need only the emitter cutover, others need the
  relation minted first.
* **What happens to `TypeFetcherGenerator`.** At zero it has no leaf dispatch left, which is a
  different file from the one `roadmap/decompose-typefetchergenerator.md` proposed decomposing.
* **The end-state guard's sequencing**, so it does not land as a wall of suppressions ahead of the
  last slice.
* **Whether the ratchet retires at zero.** A pin at zero that can only be raised by a rule violation
  is arguably a guard already, and keeping both would be two mechanisms for one invariant.

## Relationship to other items

* `roadmap/emit-plan-reads-the-store.md` is this item's planner half, already in Spec. Its own "out
  of scope" section names the emitter half as a finding to file rather than to fix there, which is
  what produced this item; the two were designed to fit together and this makes the join explicit.
  If it lands independently, this item inherits the result and is left with the emitter half alone.
* `roadmap/delivery-verdict-derives-from-the-store.md` is the planner half's declared dependency and
  transitively this item's: it derives the delivery fold and names the planning-stage consumers as
  the eligible ones.
* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` owns the model: the facts that replace
  the leaves. This item is the **consumption** side and must not redesign facts. The two meet at the
  plan tier: that item decides what a planner reads, this one decides that a planner is the only
  thing that reads it.
* The `facts-and-commands` programme (Done, see `roadmap/changelog.md`) built the
  `command` / `plan` / `render` triangle, `EmitPlan`, the command relations and these ratchets. This
  item is that programme's completion condition, not a re-run of it. Its slice logs are the
  reference for how a family migrates and what holding output identical costs.
* `roadmap/nodeid-key-projection-on-routine-params.md` carries the routine-write family's migration
  as a stage, because a feature there needed a carrier and the leaf was the wrong one. That stage is
  the worked example the emitter half generalises from. If it lands first, this item inherits a
  proven recipe and one fewer family; if this item is picked up first, that stage should be lifted
  onto it.
* `roadmap/decompose-typefetchergenerator.md` asks how to break up `TypeFetcherGenerator` and offers
  decomposing along the field taxonomy as its leading option. That option is superseded: the file
  does not get decomposed along the leaves, it empties into `render` as the families migrate. It
  should be re-scoped or discarded when this item reaches Spec, and should not be picked up
  independently in the meantime.
