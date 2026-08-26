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
now twenty registrations arranged twelve layers deep, which is a shape no author chose and no
document states; it is what a sequence of locally correct decisions happened to leave behind. A
greedy descent finds a local optimum, and the question this item asks is whether the store is
sitting in one.

The twentieth registration landed the same day this item was filed, which is the section below and
is the sharpest evidence the item has.

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
| Views in the fact schema | 107 |
| Base tables | 168 |
| Registrations in `meta_materialize` | 20 |
| Refresh depth (longest chain of registrations) | 12 |
| Derivation depth as the rule composes | 24 relations |
| Derivation depth as actually evaluated | 7 relations |

The first four figures moved when the twentieth registration landed. The two depth figures were
re-measured across both trees with a single instrument at the same time and did not move; that
instrument reads them one higher throughout, counting relations where the original counted the steps
between them, so the values above are left as they were first stated rather than restated in a
second instrument's units.

The last two are the pair that matters. Read as authored, the deepest rule in the store stands on
twenty-four relations. Read as the store actually evaluates it, with registered targets standing as
tables, the deepest read touches seven. The cut set is doing real work: it takes a twenty-four-deep
composition down to seven levels of live evaluation.

What it costs is the twelve. Every capture, and every language-server or MCP store open, pays twenty
view evaluations in twelve sequential stages, because `Materializations.refresh` walks the dependency
order and nothing in stage twelve can begin until stage eleven has finished.

## Why this looks like a local optimum

Two observations, both readable off the register itself.

The registrations were added in an order that never revisited an earlier one. Each row's reason
argues its own case against the tree as it stood, which was the right standard for that increment and
means no row is stated against the set. No registration has ever been removed. A cut set that only
grows is not a cut set anybody designed.

The write-payload family shows the descent explicitly. Its registrations occupy register layers nine
through twelve, and the as-composed depth histogram has exactly one view at each of depths sixteen
through twenty-four, which is to say that tail is a single file rather than a graph:

```
carrier_role -> payload_refusal -> payload_column -> matched_key
  -> key_membership -> write_refusal -> write_destination -> write_agreement
```

Nine rungs, each one relation wide. `intent_mutation_write_destination`'s own reason records the loop
that produced them: rewrite the rung so it can be priced, register it, and find the next rung up is
now the expensive one. That loop terminates when the rungs run out, not when the pipeline is fast.

## The twentieth registration, measured against the nineteenth

This item was filed in the morning and a twentieth registration landed the same afternoon, from an
increment whose subject was not materialization at all: the condition membership fold needed a read
of `intent_field_scope_table`, that relation was a 77-millisecond view sitting on the inner side of
the fold's final join, and it was registered. The two trees differ by that one change and nothing
else, so the arithmetic below is a clean before-and-after rather than an estimate.

```
before                                        after
  1 argmapping_pair, errors_field,              1 argmapping_pair, errors_field,
    spelled_table                                 spelled_table
  2 field_reference_step_hop                    2 field_reference_step_hop
  3 resolved_type_binding                       3 resolved_type_binding
  4 carrier_data_field, field_column_scope      4 carrier_data_field, field_column_scope
                                                5 field_scope_table              <- inserted
  5 argument_scope_table,                       6 argument_scope_table,
    mutation_write_payload                        mutation_write_payload
  6 argument_column_scope, …                    7 argument_column_scope, …
  …                                             …
 11 mutation_write_destination                 12 mutation_write_destination
```

**It did not go on top, and mid-stack is the less visible of the two failure modes.** The obvious
failure mode for a greedy cut set is a tower: each registration stacked above the last, each increment
climbing one rung, which is what the write-payload family above looks like. This one went in at stage
five with twelve registrations already above it, and moved every one of the twelve down a stage. Both
shapes cost the same single stage on the critical path, so the insertion is not the more expensive of
the two. It is the harder one to notice. A tower is legible from inside the family that builds it,
each rung visibly standing on the last; an insertion restages twelve registrations across four
families the increment never touched, none of whose files anybody edited and none of which got
faster, and nothing in the increment that caused it would show a reader that this had happened.

**The rule the family had written down was followed, and produced this anyway.** The rule is rewrite
before registering, on the ground that a registration prices a rule as it stands. One rewrite was
tried: reversing the outermost join so the scope table drives and the fold's contributor set is
probed. It measured 68349 milliseconds against the view's 6167, and the registration followed. The
rewrite that was not tried is resolving the table inside each contributor arm, so the fold has no
per-coordinate probe at the end to be slow. Trying one rewrite and then registering satisfies the
letter of the rule and is a greedy step; the rule as written does not say how hard to look, so it
cannot distinguish the two.

**The registration ritual is itself a ratchet.** A registration is now a table, a `_live` view, an
index chosen against a named reader, a comment on each, a `meta_materialize` row carrying the
measurement, and two `FactCaptureAgreementTest` entries. The index is not optional decoration: the
same fold measured 91045 milliseconds against an unkeyed target, fifteen times worse than the view it
replaced, because an inlined view can be evaluated restricted and a table can only be scanned. So
the ritual has grown as the register has, and a registration is now a larger schema commitment than
one made early in the register was. That raises the cost of ever taking one out, which is a mechanism
for a set that only grows.

**The discipline does sometimes say no, and it is worth recording how often.** Of the four increments
before this section was written, three added a registration and one refused, that one having measured
that the rule it was pricing was cheaply restrictable and that an unkeyed table would have been fifty
times worse than leaving it a view. The measurement is real and it does argue both ways. What it
never argues is against the set, because there is nothing in any single measurement that can.

## What is not known

Nothing here says the cut set is wrong, and this item should not be read as claiming it is. Three
things are simply unmeasured.

What the register costs as a whole. Every row prices its own refresh against its own readers. No
figure anywhere states what the twenty refills cost together, or what the twelve-stage serial path
contributes over the same work unordered.

Whether a smaller set would do as well. The obvious alternative, three or four cuts at the widest
fan-out shoulders instead of twenty wherever a build hurt, has never been built or measured. It may
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
