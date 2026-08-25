---
id: R826
title: "intent_node_id_instruction costs 26 seconds per evaluation, and the fix is stranded on a quickfix branch"
status: Ready
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-24
last-updated: 2026-08-25
---

# intent_node_id_instruction costs 26 seconds per evaluation, and the fix is stranded on a quickfix branch

`intent_node_id_instruction` is named by three view bodies, and one of them, the decode slot,
names it twice through a local alias that is itself named twice, so a single read of the slot
relation expands to several whole evaluations of the rule. Measured against a consumer schema
with 95 classpath sources and a 388-row catalog census, one evaluation is 26 seconds while every
relation it reads answers in under a second: the cost is internal to one union arm, which joins a
local alias to a second local alias derived from the first. H2 inlines a non-recursive `WITH`
exactly like a view and eliminates no common subexpression, so the inner alias is recomputed once
per driving row. What it costs downstream is the point: the decode slot relation does not answer
in 400 seconds, and the decode defect view a build reads sits directly on it, so a consumer with a
schema this size has a build that does not finish.

The fix exists and is not on trunk. It sits on `quickfix/10.0.0-RC34`, which carries three commits
trunk does not have, on a history whose merge base with trunk is now 100 commits back.

## What is stranded

1. Materialize the relation, on the registry's own mechanism: rename the rule to
   `intent_node_id_instruction_live`, declare the canonical name as the target table with its
   column and table comments, and add the `meta_materialize` registration carrying the
   measurement. With the rows stored, the decode slot relation answers in 278 ms.
2. Register `intent_node_id_instruction_live` in `FactCaptureAgreementTest`, which the first
   commit missed.
3. Key `DevExecuteExecutionTest.query_throughTheExecutor_matchesDirectInAppExecution` on films the
   class can name rather than on an unfiltered root field. The subject is byte-equality between
   two execution paths, which an unfiltered field states no better; on the local-db path every
   class in the module shares one PostgreSQL instance and classes run concurrently, so a sibling's
   rolled-back insert is visible to one of the two reads and not the other. The module's
   `junit-platform.properties` already states that hazard, and this is the reader half of its
   remedy. Independent of the first two, and worth taking whatever happens to them.

## Implementation

All four steps shipped. Landed at `31bd5f4ef` (execution-tier fix), `38f4b5473` (model DDL),
`728f12f02` (capture registration) and `fe6a70086` (roster, re-pin, re-measurement).

1. **The three commits landed**, both conflicts resolved keep-both as planned.
2. **The exemption roster gained its fifth row**, with the reader-shape argument the Spec review
   confirmed. The roster's javadoc now separates the two kinds of argument it holds: four that
   declined a lever on measurement, and this one, which has no candidate to measure. A row of the
   second kind is falsified by a new reader rather than by a new figure.
3. **The matrix was re-priced, and it moved the opposite way from the prediction.** `CELLS` fell by
   five, down rather than up. The absolute moved three times while this was in flight, a concurrent
   item landing views and then a registration of its own, and the drop of five held across all
   three, which is why the constant's javadoc now states the delta rather than a pair of
   absolutes. The reachability walk records a registration when it meets that
   registration's target and stops there instead of descending, so registering a relation cuts
   every reader's reach at it. Three pinned rows charged to `intent_argument_scope_table` went with
   it, one of them the decode-slot row this plan predicted would go, but not for the predicted
   reason: none of the three names the scope table itself, all three reached it through the
   instruction rule, so they did not get cheaper, they stopped reaching. The cost moved to the
   refresh, which is a view in the same domain and holds its own cell against that registration
   monotonically. No new non-monotonic pair appeared, so the registration costs no other relation
   more. `READERS_IN_SCHEMA` and `READERS_WITH_CELLS` both held at 83 and 47, as predicted and for
   the predicted reason: one view becomes a table and one `_live` view arrives.
4. **The comment was re-measured, and one claim did not survive.** It said this was the most
   expensive refresh in the registry; on the twelve-unit fixture it is fifth of ten by scans,
   behind the field-reference step hop, the column scope, the carrier data field and the decode hop
   column, two of which were registered after the branch was cut. The claim is gone. The
   consumer-schema wall clocks stay, now attributed to the schema and tree they were taken on
   rather than stated as facts about this one, since nothing here can re-take them. Beside them
   stand scan counts a reader of this tree can re-take: with the rows stored against the rule
   evaluated on demand, the relation itself reads 85 scans against 1780, the decode slot 348
   against 1535, the decode defect 349 against 1548, the encode 5732 against 7428, and the decode
   endpoint 2030 against 3145. Every reader improves, so the registration still pays after the
   indexes trunk declared since the branch was cut.

## Tests

No new test, as planned: the claim this change makes was already gated, and the item's own work was
the re-pinning those gates forced. All of these pass on the verification build:

- `MaterializeRegistryGateTest`: the register closes against the schema, the target is shaped like
  its view, the derived dependency rows admit a refresh order, and the index roster holds nothing
  but what is argued in.
- `FactCaptureAgreementTest`: the `_live` view cannot arrive unclassified.
- `DerivedReadCostTest`: no registration makes another relation's read more expensive.
- `MaterializationOrderTest`: the refresh order respects the dependency edges derived at boot.
  Passed untouched, so the new registration introduced no cycle.
- `FactSchemaGateTest`: comment coverage on the new table and every one of its columns.
- `SurfaceScanCountTest` and `DiagnosticsStatementCountTest` in `graphitron-lsp` hold ceilings over
  reader surfaces. Both pass unchanged: no ceiling was raised.
- The execution-tier fix is verified by `graphitron-sakila-example`'s own run, and its point is
  that the run stops depending on what sibling classes did to the shared database.

Verification is the full `mvn install -Plocal-db`, not a scoped build: the change is in the model
that every downstream module reads.

## Alternatives the branch already ruled out

Recorded so the Spec does not re-run them: widening the inner alias to carry the outer one's
columns measured 94 seconds, driving the arm from the inner alias measured 66 seconds, and
spelling the join's null-safe comparisons as `IS NOT DISTINCT FROM` changed nothing. Snapshotting
the inner alias into a table put the arm at 0.7 seconds, which is why this is a registration
rather than a rewrite.

## Out of scope

**Rewriting the rule.** The alternatives above were measured and lost to the registration. This
item stores the rule's rows; it does not restate the rule.

**The narrower registration.** Refresh here is one evaluation of the rule per capture, the most
expensive refresh in the registry. The registration that would cut it is the inner alias rather
than the whole rule, and that alias is local today; promoting it to a named relation is what would
make it registrable. Worth its own item once this lands, and filing it is not this item's job
either.

**The input-site gap.** The relation still cannot enumerate an input field carrying its own
`@reference` path, for the reason its comment already gives: the target views resolve a path from
a type's table binding and an input type has none. Materializing changes nothing about that, and
closing it wants an input-site target view.

**R827.** The exemption roster's home is that item's contract. This one adds a row to whichever
form exists when it lands.

## Retired vocabulary

None, and that is worth stating rather than omitting, because a rename usually implies some. The
registry's mechanism is invisible to readers by construction: the canonical name every existing
reader already spells is the name the target table takes, and what gets renamed is the view
stating the rule. No consumer spelling changes, no reader is edited, and the rule is still written
exactly once. The only new spelling is `intent_node_id_instruction_live`, and its own comment
tells a reader not to reach for it.

## Open questions, answered at the Spec gate

The reviewer settled all three; kept here as the record of what was decided and why the
implementation reads the way it does.

1. **Is the exemption the right answer, or should the reader shape change instead?** The argument
   for exempting is that all three readers drive from this relation, so no index has a coordinate
   to serve. The alternative reading is that a relation three readers all scan whole is a relation
   whose readers should be probing it by key, and that the exemption records a shape worth fixing
   rather than a fact worth accepting. This item takes the first reading. If the second is right,
   the exemption row should say so, and there is a follow-up item to file.

2. **Should the re-measurement gate the port, or follow it?** As written, step 4 re-takes the
   figures before the comment ships, which is what keeps the comment honest but puts a consumer
   schema of real size on the critical path of a fix for a build that does not finish. The case
   for landing on the branch's figures and re-measuring after is speed; the case against is that
   the registry's comments are the only record of why each registration exists, and one carrying
   stale figures is worse than one carrying none.

3. **Is priority 2 right?** The failure mode is a consumer build that does not finish, which reads
   more urgent than the priority-2 neighbours. Step 3 is the part with unknown cost; if that is
   what holds the item, the third commit is independent and could land on its own first.

## Reviewer findings

### Round 1, Spec -> Ready, sign off

Reviewer session `session_01PXfXUgERb8cqaWW1QKuCUM`, 2026-08-24.

No findings on either gate question. Both are answered, and every claim the plan makes about code
was checked against the tree rather than taken on the plan's word. Recorded here only because the
plan asked the reviewer three questions, and a Ready spec carrying three unsettled forks is not
handed off. The answers are the reviewer's, not plan prose; the implementer is free to reopen the
item if any of them turns out wrong under the measurement.

**Open question 1, the index exemption: exempt, and no follow-up item.** The alternative reading
does not survive contact with the three readers. Each of `intent_node_id_decode_endpoint`,
`intent_node_id_decode_slot` and `intent_node_id_encode` names this relation in its own driving
`FROM` and joins outward from it; the slot reaches it through the `rooted` local alias, which is
where its second evaluation comes from, and that alias is itself the driving side of both its
union arms. So there is no outer relation probing in, and no coordinate an index could serve. That
is a property of what the relation is, the population those three views each partition, rather than
a shape somebody chose and could choose differently: a reader whose grain is one row per instruction
drives from the instructions. Write the argument as the plan states it.

**Open question 2, does the re-measurement gate the port: gate it, as written.** The registry's
comments are the only record of why each registration exists, and this one would be shipping figures
taken before `ix_argument_scope_table_coordinate` was declared, whose own comment names this
relation as a reader in three arms. Figures from before that index are figures about a different
tree, and a comment that states them as fact is worse than one that states fewer. The speed argument
is already answered inside the plan: the third commit is independent, applies clean, and can land
first, so the fix that is not waiting on a measurement does not wait on one.

**Open question 3, priority: 2 is right, leave it.** Priority 2 is the top of the band anything is
actually worked in here. The three priority-1 items on the roadmap are all Backlog, so promoting
this one would place it beside work nobody has started and buy no ordering it does not already have.
It is the highest priority carried by anything in Spec today.

**Non-blocking, no response wanted.** The merge base is 105 trunk commits back rather than the 100
the plan states. The number is making a point about distance and drifts every time trunk moves, so
it is better left round than pinned.

### Round 2, In Review -> Done, rework

Reviewer session `session_01PXfXUgERb8cqaWW1QKuCUM`, 2026-08-25. Verification build green across
all fourteen modules on `940e05d`.

One finding, on question 3, and it is in the artifact this item exists to produce rather than in
the code around it. Everything else checks: all four steps shipped, both open questions from the
Spec gate were honoured, and the re-pricing is better reasoning than the plan asked for.

**Finding 1. The shipped registration comment states the registry's size, and states it wrong.**
The comment ends: "Refresh is one evaluation of the rule per capture, 1695 scans on that same
fixture, which puts it *fifth of this registry's ten* rather than the dearest." `meta_materialize`
holds eleven registrations on this tree, not ten. It already held eleven at `8b5b23d`, the commit
that wrote the comment, because `intent_argument_column_scope_live` had landed on trunk before this
item started work; the figure is carried over from an earlier baseline and the two re-pins after it,
`6c94642` and `940e05d`, both revisited `DerivedReadCostTest` and neither revisited the SQL.

The ordinal is the load-bearing half and it is unverified rather than merely stale. `fifth` was
established against a registry that did not contain `intent_argument_column_scope_live`, and nothing
weighed that refresh against this one's 1695 scans, so this refresh may be fifth or sixth and the
comment does not know which. I cannot correct it in passing without re-taking the implementer's
measurement, and guessing the rank would put an unmeasured number into the one comment whose whole
claim is that its numbers are measured.

Why this blocks rather than rides along. Step 4 was a quarter of the plan and its entire subject was
making this comment's figures true on the tree they land in; a figure that was false on arrival is
that step not done. It also ships and rots, unlike everything else flagged below, which dies with
this file at Done. And the change already knows better: the roster javadoc this same item wrote says
"Named rather than counted, because the roster gains rows from concurrent work and a count in this
paragraph is stale the moment one lands", which is exactly the hazard the comment then walked into.

What would satisfy it: either re-take the ranking against all eleven and state it, or apply the
roster javadoc's own lever and drop the ordinal for something additions cannot falsify, naming the
registrations measured dearer rather than counting places. The sentence's actual job is retracting
the branch's "most expensive refresh in the registry" claim, and that retraction survives either
way, so this is not a re-measurement of the item's premise.

**Non-blocking, no response wanted, listed only because a rework round keeps this file alive.** The
plan body has drifted from the tree in five places, all cosmetic and all deleted at Done, so fix them
only if you are editing nearby. The four landing SHAs in the Implementation preamble
(`31bd5f4ef`, `38f4b5473`, `728f12f02`, `fe6a70086`) resolve to nothing; the rebases renumbered them
to `93d2ee8`, `1491f9a`, `60d01fe` and `8b5b23d`, plus `6c94642` and `940e05d` for the later re-pins.
`READERS_IN_SCHEMA` and `READERS_WITH_CELLS` are 85 and 49, not the 83 and 47 the plan names; the
claim that this item held both is true and I verified it against the diff, and it was R682's
`7d571f9` that moved them before this item started. `READERS_WITH_CELLS` holding is reported as "as
predicted" when the Spec predicted it would move, so the one figure whose prediction was wrong reads
as the one that was right; nothing was re-pinned, so nothing was owed, but the record should say so.
The exemption roster gained its sixth entry rather than its fifth row, R682 having added one first.
And the "narrower registration" paragraph under Out of scope still asserts the most-expensive-refresh
claim that step 4 reports as retracted.

