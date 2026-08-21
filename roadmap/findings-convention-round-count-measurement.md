---
id: R779
title: "Did findings-not-fixes cut the bounce loop? Measure the plan-body blame fraction over three bounced items"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Did findings-not-fixes cut the bounce loop? Measure the plan-body blame fraction over three bounced items

R775 makes the Spec → Ready reviewer hand back findings instead of editing the plan, on the
diagnosis that reviewer-authored plan prose is what feeds the bounce loop. That diagnosis is
falsifiable and this item is where it gets tested, on data that does not exist until R775 has been
Done for a while. R775 carries no build gate and its own Done gate turns on the prose landing, so
without this item the bet is never settled.

Pick this up once three items have bounced at Spec → Ready under the new convention.

## The measurement

For each of the next three bounced items, at the moment it reaches Ready, record two numbers.

**The plan-body blame fraction.** The fraction of surviving lines *above* the
`## Reviewer findings` heading that were written by a reviewer session. Measure the plan body and
not the file: a compliant bounced item gains reviewer-written lines by construction, because
findings are reviewer-written, so blaming the whole file reads success as regression. R769 is the
worked demonstration of that trap. Its whole-file fraction moved 49% → 61% across a fourth pass
that followed the convention exactly (81 insertions, zero deletions, plan body untouched), while
its plan body held at 134 of 273 lines, or 49%.

```bash
slug=<item>.md
cut=$(grep -n '^## Reviewer findings' "roadmap/$slug" | head -1 | cut -d: -f1)
head -n $((cut - 1)) "roadmap/$slug" > /tmp/body.md
git blame -L "1,$((cut - 1))" --line-porcelain "roadmap/$slug" \
  | grep -oE '^[0-9a-f]{40}' | sort | uniq -c
```

Attribute each commit to author or reviewer by its `https://claude.ai/code/session_<id>` trailer
against the item's own history, the same identifier the reviewer-rule guard compares.

**The round count.** How many times the item bounced before reaching Ready.

## The baseline, and what each outcome means

R769 under the old convention is the baseline: 49% of its plan body written by reviewers, three
bounces, two of them on prose a reviewer had written minutes earlier.

The convention is working if the plan-body fraction is at or near zero and the round count falls.
Any reviewer-written line in a plan body is the convention not being followed, and the reason is
worth reading before concluding anything about the bet.

**The falsifier, stated in R775 and repeated here so it survives that file's deletion.** The change
trades one extra session per revision round for fewer rounds: a three-round item goes from three
sessions to six if the round count holds flat, and pays for itself only if the count falls. If all
three items still take three or more rounds *and* their findings are about author-written prose
rather than reviewer-written prose, R775's diagnosis is wrong, the loop has another cause, and this
item's deliverable is naming that cause rather than defending the convention.

## Prior context worth keeping

Round counts before the change, from `roadmap/changelog.md`: R759 cleared in one round, R732 took
five, R769 took at least three. So the pre-change spread is wide and three items is a small sample.
Treat a round-count drop as suggestive and the plan-body fraction as the load-bearing number, since
that one measures the mechanism directly rather than its hoped-for effect.

## What this item deliberately does not do

**It does not add a build gate.** R775's Tests section gives the reason: a grep for the heading
would fire on every clean spec, which is the wrong enforcer for a convention that only applies when
a review withholds. A *spelling* gate is different and is separately viable once the two items on
legacy spellings drain; that is its own Backlog item, not this one.

**It does not re-litigate R775's design.** If the measurement comes back bad, the deliverable is the
finding, not a revert.
