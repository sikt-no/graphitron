---
id: R824
title: "Break the build when the reactor exceeds a wall-clock budget"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# Break the build when the reactor exceeds a wall-clock budget

Every gate in this repo is a boolean. The verification build, the enforcer rules, the doclint
reference check, the citation guards, the coverage ratchets: each of them answers pass or fail, and
none of them answers "how long did that take". A change that leaves the build correct but four times
slower is green everywhere, so nothing stops it reaching trunk and nothing announces it once it is
there. We want a gate that fails the build when the reactor takes longer than twenty minutes.

This is not hypothetical. Trunk ran at roughly eighteen minutes through 2026-08-23, then `4b9ddce`
wired the routine-write producer to read the store and the next trunk run took 54m44s, of which the
reactor build step alone was 48m47s. The following commits widened the schema fixtures and trunk
settled at a floor between 82 and 89 minutes, where it stayed for eleven consecutive runs across
roughly fourteen hours. Eight of those eleven commits changed only roadmap front-matter, docs, or
spec text, which is the shape that should have been impossible to miss: a docs commit does not get
slower unless the floor moved under it. All eleven runs reported success. R819 diagnosed the cause
and its two materialization registrations brought trunk back to between thirteen and nineteen
minutes, so the regression is closed; what is not closed is that nothing except a human noticing
made it visible, and the thing a human noticed was a local build dying rather than the number itself.

Four things a spec should settle rather than assume.

Where the gate sits. A JUnit test cannot observe the wall clock of the build that contains it, so
"test" here is shorthand for a check, not literally a member of a test tier. The two plausible seats
are a Maven execution bound late in the reactor that compares now against `MavenSession.getStartTime()`
and fails with a clear message, and a `timeout-minutes` on the CI job that kills the runner. The
first works locally as well as in CI and produces a diagnosable failure but only fires after the full
cost has been paid; the second is one line but discards artifacts and reports as a timeout rather
than as a budget breach. They are not exclusive.

What the budget applies to. The GitHub runner, this project's web sandbox, and a contributor's laptop
are not comparable machines, and a twenty minute budget calibrated on one of them is wrong on the
others. The likely answer is a property that CI sets and local builds leave off by default, but that
is a decision, not an assumption, and the alternative of measuring something machine-independent
(statements issued, relations scanned) deserves weighing first.

What the number means. Twenty minutes is the figure asked for and it sits just above the observed
thirteen-to-nineteen band, which makes it a tight budget with little headroom for legitimate growth.
A spec should decide whether it is a fixed constant that gets raised deliberately when the reactor
honestly grows, or a ratchet against a recorded baseline, and where that number lives so raising it
is a visible commit rather than a quiet edit.

How it fails. A gate that fires at the tail of an 85 minute build has already cost the contributor
85 minutes. Whether the check can be made to fire earlier, per module or against a phase budget,
changes how useful it is in practice and is worth at least a paragraph.

The companion structural rule is deliberately out of scope here. The cause of this particular
regression was an unmaterialized view read from the generate path, and a meta-test asserting that
every relation a plan-tier facts class names is either registered in `meta_materialize` or explicitly
exempted would have caught it before it landed, deterministically and with no wall-clock involved.
That is a better gate for this cause and a worse gate for the general case, since it catches only
store-read regressions. It should be its own item; `intent_mutation_routine_seat` is still an
unregistered view on the generate path and would be its first finding.
