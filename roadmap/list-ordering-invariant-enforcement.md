---
id: R677
title: "Enforce the never-unsorted-list invariant off the launcher relation ordering slot, where every leak site is visible"
status: Backlog
bucket: validation
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
---

# Enforce the never-unsorted-list invariant off the launcher relation ordering slot, where every leak site is visible

Graphitron states an invariant: a list result is never unsorted. One check enforces it,
`GraphitronSchemaValidator.validateListRequiresOrdering`, and it keys on two signals that most of
the known violations do not produce. It fires on `OrderBySpec.None`, and it only sees members of
`SqlGeneratingField`. Every leak found so far either sits outside that capability or carries a
populated ordering spec that a later layer discards, so the check passes and the rows ship in
whatever order the database happened to return.

Five sites are known today:

* Root `@routine` chain: **closed** by `roadmap/routine-composition-surface-from-facts.md`. The
  escape was a carve-out, not a capability gap: the leaf is a `SqlGeneratingField` (the source
  axis folded onto the shared root read leaf), and the rule named the `RoutineResolution.Chain`
  arm as an explicit exemption. Removing the exemption closed the site; the four sites below are
  the live census.
* `@splitQuery` child list: a resolved `OrderBySpec.Fixed` is discarded at the model-to-command
  boundary (`roadmap/split-query-child-list-drops-default-order.md`).
* `@lookupKey` child: `LauncherCommands.batchedLookupRow`'s ordering slot is left empty
  (`roadmap/lookup-unrealized-co-members.md`).
* Mutation routine write path: unordered step 2
  (`roadmap/routine-write-key-capture-unordered.md`).
* Root query over a multitable interface or union: the arm carries no ordering component at all,
  so `@orderBy` and `@defaultOrder` are accepted and discarded and rows come back in participant
  primary-key order (`roadmap/multitable-interface-query-orderby-lowering.md`). `QueryInterfaceField`
  and `QueryUnionField` declare no `orderBy` component, which is why they do not implement
  `SqlGeneratingField` and why both ordering checks skip them: a paginated multitable root with no
  ordering passes `validatePaginationRequiresOrdering`, the check written to reject exactly that
  shape.

Only the first produced `None`, and it is now enforced. The rest are invisible to the enforcer
by construction, which is what this item is for.

**One of those four sites is not in the launcher relation, so this item's premise does not cover
it.** The title says the ordering slot is where every leak site is visible. That holds for three:
the `@splitQuery` child, the `@lookupKey` child and the routine write path are all launcher rows
with a `ResultShape`. The multitable polymorphic root is not. `LauncherCommands.verdictOf` anchors
on the target-axis fact `TargetShape.Table`, and the multitable family carries `Interface` /
`Union`, so it takes no launcher row at all; its javadoc states the exclusion directly, that "its
UNION-ALL stage belongs to the polymorphic-emit family, roots and batched children alike".
Re-sourcing off the launcher relation would therefore leave that site exactly as invisible as it is
today, while the item's framing would read as having closed it. Whoever specs this has to either
name a second source that covers the polymorphic-emit family, or narrow the claim to the launcher
population and say plainly that the multitable root needs its own enforcement. Do not discover this
after the re-sourcing is written.

## Why this is its own item

Two items independently reached the same conclusion and both declined to carry it. The routine
chain item states the class as "an ordering the model resolved does not reach the emitted SQL, and
no check compares the two ends", notes that its own fix cannot make the invariant true, and says
the shared enforcement question should be "its own item rather than as a rider on either". The
`@splitQuery` item repeats it and adds that the launcher relation is where every leak site is
visible in one place, as `ResultShape.RecordList` with an absent `Ordering`. A third item
(`roadmap/root-family-validator-mirror-gaps.md`) proposed the same re-sourcing, but scoped to its
own bullet, the routine-chain membership gap, not to the invariant; that bullet has since been
closed by removing the carve-out, which leaves the invariant exactly where it was.

So the fix has been named three times from three coordinates and owned by none of them. That is
the gap this item closes: re-source the rule off the launcher relation's ordering slot, so
membership in a capability and the value of a classify-time enum stop deciding whether the
invariant is checked.

## The second half: declared orderings that cannot be lowered

Re-sourcing catches lists that arrive unordered. It does not by itself catch the other shape a
consumer reported, an ordering the author *declared* at a coordinate that cannot lower it. On a
root query over a multitable interface, `@orderBy` and `@defaultOrder` classify clean, generate
without a diagnostic, and produce participant-primary-key order at runtime, while `@condition`
filters on the same field work. The consumer reads that as a runtime bug rather than an
unimplemented surface, and reasonably: nothing told them otherwise.

Their fallback ask is the honest one and belongs here. Until lowering ships, a schema that
declares an ordering contract the target coordinate cannot honour should fail the build, naming
the coordinate, the way an unsupported `@condition` overload does. A build error is recoverable
in minutes; silently wrong row order is found in production, if at all.

The same reasoning covers the worst case already recorded on the `@splitQuery` item: for a
view-backed target with no primary key, the deterministic-order validator *compels*
`@defaultOrder` and emit then discards it. The build states the invariant and breaks it in the
same run. Whatever this item ships has to make that combination impossible.

## Sequencing

Deliberately no `depends-on`. A single-signal enforcement turned on today would fail builds of
schemas that are legitimate once the per-site fixes land, so the two halves sequence differently:

* The declared-but-unlowerable rejection can ship immediately and independently. It turns a silent
  wrong answer into a build error at coordinates where nothing is going to lower the ordering soon,
  and it is what unblocks the consumer who reported it.
* The re-sourced enforcement lands after or alongside the per-site fixes, and its value is that it
  makes those fixes non-regressable and closes the sites nobody has found yet. Whether it can go
  green before every listed site is fixed, or needs a temporary exemption list, is a Spec question.
  An exemption list is acceptable only if each entry names the item that removes it.

## Notes for whoever specs this

- The point of re-sourcing is that the launcher relation makes the population observable without
  asking each coordinate to remember to declare itself. Any design that still needs a per-arm opt
  in has reproduced the current bug in a new place.
- **The two halves read different signals, and only one of them is a launcher-relation question.**
  Re-sourcing asks "did this list-shaped command arrive with an ordering", which the launcher
  relation answers for the population it covers. The honesty rejection asks a different question:
  "did the author declare an ordering at a coordinate whose classified arm cannot hold one". That
  comparison is between an authored fact and an arm's capability, and it is decidable before any
  command row exists. Treating the two as one mechanism is what would make the rejection wait on
  the re-sourcing, which the Sequencing section above is explicit it must not do.
- That makes the honesty half the same shape as the fan-out verdict in
  `roadmap/reference-path-fanout-verdict.md`: a build-time verdict comparing what the author
  declared against what the pipeline can honour, decidable from captured facts rather than from a
  traversal. `graphitron_default_order` is already captured, so the rejection is plausibly a
  store-derived rule in `rewrite/derive/` with `AuthoredClaimConflicts` as its precedent, not a new
  arm in the validator switch. Worth pricing that way before defaulting to the validator, since the
  validator is where the current gap comes from. One difference to keep straight: the fan-out
  verdict is advisory, because a multiset may be what the author wanted, while this one is a hard
  rejection, because nothing can honour the declaration. Same derivation, different severity.
- `docs/manual` should say what the invariant guarantees and where it does not hold yet. Today the
  Sorting and polymorphic-query pages state no limitation, which is how the consumer arrived at a
  runtime surprise.
- A meta-test over the launcher relation's arms, asserting that every list-shaped arm either
  carries an ordering or is a named exemption, would keep a sixth leak site from appearing quietly.

The honesty half is the reported half: https://github.com/sikt-no/graphitron/issues/523.
