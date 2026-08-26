---
id: R848
title: "Design the materialization cut set as a whole instead of accreting it one registration at a time"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# Design the materialization cut set as a whole instead of accreting it one registration at a time

Every materialization registration in the fact store was added on its own measurement, and each of
those measurements was sound. Nobody has ever evaluated the resulting set as a set. The register is
now nineteen registrations arranged eleven layers deep, which is a shape no author chose and no
document states; it is what a sequence of locally correct decisions happened to leave behind. A
greedy descent finds a local optimum, and the question this item asks is whether the store is
sitting in one.

## Vocabulary

A **derived relation** is a view in the fact store: a rule stated once in SQL, evaluated whenever a
reader names it. A **registration** is a row of `meta_materialize` that keeps the rule in a view and
moves the canonical name every reader spells onto a table of the same shape, which the materializer
refills once per capture. The **cut set** is the set of relations chosen for registration: the points
along the derivation where evaluation stops and a stored answer starts. **Refresh depth** is how many
of those refills must run in sequence, a registration whose view reads another registration's target
being unable to start until that one has finished.

## What the store says today

Measured against the store a build left on disk, not against a fixture:

| Figure | Value |
|---|---|
| Views in the fact schema | 106 |
| Base tables | 167 |
| Registrations in `meta_materialize` | 19 |
| Refresh depth (longest chain of registrations) | 11 |
| Derivation depth as the rule composes | 24 relations |
| Derivation depth as actually evaluated | 7 relations |

The last two are the pair that matters. Read as authored, the deepest rule in the store stands on
twenty-four relations. Read as the store actually evaluates it, with registered targets standing as
tables, the deepest read touches seven. The cut set is doing real work: it takes a twenty-four-deep
composition down to seven levels of live evaluation.

What it costs is the eleven. Every capture, and every language-server or MCP store open, pays
nineteen view evaluations in eleven sequential stages, because `Materializations.refresh` walks the
dependency order and nothing in stage eleven can begin until stage ten has finished.

## Why this looks like a local optimum

Two observations, both readable off the register itself.

The registrations were added in an order that never revisited an earlier one. Each row's reason
argues its own case against the tree as it stood, which was the right standard for that increment and
means no row is stated against the set. No registration has ever been removed. A cut set that only
grows is not a cut set anybody designed.

The write-payload family shows the descent explicitly. Its registrations occupy register layers eight
through eleven, and the as-composed depth histogram has exactly one view at each of depths sixteen
through twenty-four, which is to say that tail is a single file rather than a graph:

```
carrier_role -> payload_refusal -> payload_column -> matched_key
  -> key_membership -> write_refusal -> write_destination -> write_agreement
```

Nine rungs, each one relation wide. `intent_mutation_write_destination`'s own reason records the loop
that produced them: rewrite the rung so it can be priced, register it, and find the next rung up is
now the expensive one. That loop terminates when the rungs run out, not when the pipeline is fast.

## What is not known

Nothing here says the cut set is wrong, and this item should not be read as claiming it is. Three
things are simply unmeasured.

What the register costs as a whole. Every row prices its own refresh against its own readers. No
figure anywhere states what the nineteen refills cost together, or what the eleven-stage serial path
contributes over the same work unordered.

Whether a smaller set would do as well. The obvious alternative, three or four cuts at the widest
fan-out shoulders instead of nineteen wherever a build hurt, has never been built or measured. It may
be worse; the point is that no one knows.

Whether the depth itself should fall. Twenty-four is the number that forces the register to exist at
all, H2 inlining a view wherever it is named and eliminating no common subexpression, so depth is
what turns a rule named twice into a rule evaluated twice. Most of that depth reads as genuine
composition and is stated at the fine granularity this schema deliberately prefers. The linear tail
above is the part that does not.

## Shape of the work

A Spec should decide between two framings before it plans anything, because they lead to different
work. Either the depth is essential and the cut set is an execution plan that should be designed
top-down and pinned, or the depth is partly accidental and folding rungs removes the need for some of
the registrations outright.

Whichever it is, the instrument already exists and is cheap. `MaterializeDependencies` computes the
reach and the ordering from a booted store, and `DerivedReadCostTest` already builds a baseline store
plus one store per registration with `UnregisteredRelation` installed. Candidate cut sets can be
scored without inventing a harness.

Related: the value census of individual registrations is a narrower question that takes the current
cut set as given, and R831 files the same drift problem for the measured claims written into ordinary
relation comments.
