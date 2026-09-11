---
id: R944
title: "The read-cost gate fails on how loaded the machine is, which has held trunk CI red and the docs site undeployed since 2026-09-09"
status: Backlog
bucket: testing
priority: 1
theme: testing
depends-on: []
created: 2026-09-11
last-updated: 2026-09-11
---

# The read-cost gate fails on how loaded the machine is, which has held trunk CI red and the docs site undeployed since 2026-09-09

## Goal

Trunk CI is green again, and the docs site deploys again. Both have been down since
2026-09-09 11:20: every `Rewrite reactor CI` run on `claude/graphitron-rewrite` since then has
failed, so `docs-build` and `docs-deploy`, which are gated on the `build` job, have been *skipped*
rather than run, and `graphitron.sikt.no` has not been republished for two days. Nothing is wrong
with the documentation. The docs jobs are collateral, and anyone reading the run list sees docs not
happening and infers a docs problem, which is the second thing this item fixes.

The failing assertion is `DerivedReadCostTest.theCellsThatCouldNotBeComparedAreExactlyTheOnesRecorded`.
That gate prices each derived relation with a registration on and off and compares rows visited; a
cell whose unregistered side does not answer inside a relative wall-clock budget is recorded as
unmeasurable instead of compared, and `KNOWN_EXHAUSTED` pins the recorded set at empty. On the CI
runner two cells exhaust it, `intent_carrier_data_field|diagnostic` and
`intent_carrier_data_field|intent_field_unlowerable_ordering`, so the pin fails. Locally the same
class is green.

The defect is in the premise, not in the number, and the pin's own javadoc states the premise it has
stopped satisfying: `BUDGET_FLOOR_MILLIS` "is set clear of the slowest of them so that which cells
appear here is a fact about the code and not about how loaded the machine was". On a shared GitHub
runner it is not clear of the slowest, so the gate now reports a machine fact as a code fact. Raising
the floor until CI passes is the move to refuse: it weakens the one gate whose job is noticing a
reader getting dearer, and it would have to be raised again on the next slower runner.

That this is environmental rather than a regression is established rather than assumed. The identical
assertion fails with the identical two cells on `34349521966`, whose commit is a *roadmap-only* diff
touching one markdown file, so no code change can account for it. It also long predates R939, whose
registration landed 2026-09-10 13:20 and which moved this test's other pins without touching these
cells.

Two things this item owes beyond the fix. Whatever replaces the wall-clock cut has to keep the arm
that `aCellThatCannotAnswerIsRecordedRatherThanFailed` exercises, because a genuinely non-terminating
unregistered side must still pass rather than fail: non-termination is the strongest form of
"materializing did not make this worse". And the docs jobs should say why they did not run, since a
skipped deploy currently reads the same as a deploy nobody asked for.
